package tracker

import cats.effect.IO
import doobie.implicits.*
import doobie.util.fragment.Fragment
import munit.AnyFixture
import munit.catseffect.IOFixture
import tracker.db.Database

import java.sql.SQLException

class DbPrivilegesSpec extends munit.CatsEffectSuite:
  private val xa: IOFixture[doobie.Transactor[IO]] =
    ResourceSuiteLocalFixture("postgres", TestDatabase.transactor)

  override def munitFixtures: Seq[AnyFixture[?]] = List(xa)

  private val InsufficientPrivilege = "42501"

  private def assertDenied(statement: String): IO[Unit] =
    Fragment
      .const(statement)
      .update
      .run
      .transact(xa())
      .attempt
      .map {
        case Left(error: SQLException) =>
          assertEquals(error.getSQLState, InsufficientPrivilege, s"$statement: ${error.getMessage}")
        case other =>
          fail(s"Ожидался отказ в правах для «$statement», получено: $other")
      }

  private def scalar[A: doobie.Read](query: String): IO[A] =
    Fragment.const(query).query[A].unique.transact(xa())

  // Пункты из ТЗ: DROP / CREATE / TRUNCATE под tracker_app запрещены.
  List(
    "DROP TABLE users",
    "DROP TABLE sessions CASCADE",
    "DROP TABLE habits CASCADE",
    "DROP TABLE checkins",
    "CREATE TABLE evil (id int)",
    "TRUNCATE users",
    "TRUNCATE sessions",
    "TRUNCATE habits CASCADE",
    "TRUNCATE checkins",
    "TRUNCATE users CASCADE",
    "ALTER TABLE users ADD COLUMN evil int",
    "CREATE TEMP TABLE evil (id int)",
    "CREATE INDEX evil ON users (created_at)",
    "CREATE SCHEMA evil",
    "CREATE EXTENSION IF NOT EXISTS pgcrypto",
    "CREATE ROLE evil",
    "ALTER ROLE tracker_app SUPERUSER",
    "DELETE FROM flyway_schema_history",
    "UPDATE flyway_schema_history SET success = false"
  ).foreach { statement =>
    test(s"tracker_app не может выполнить: $statement") {
      assertDenied(statement)
    }
  }

  test("tracker_app не может раздавать права: GRANT не меняет ничего") {
    // Без grant option Postgres не падает, а лишь предупреждает, поэтому проверяем результат.
    for
      _ <- Fragment.const("GRANT SELECT ON users TO PUBLIC").update.run.transact(xa())
      grants <- scalar[Long](
        "SELECT count(*) FROM information_schema.role_table_grants " +
          "WHERE table_name = 'users' AND grantee = 'PUBLIC'"
      )
    yield assertEquals(grants, 0L)
  }

  test("tracker_app не может читать историю миграций") {
    Fragment
      .const("SELECT count(*) FROM flyway_schema_history")
      .query[Long]
      .unique
      .transact(xa())
      .attempt
      .map {
        case Left(error: SQLException) =>
          assertEquals(error.getSQLState, InsufficientPrivilege)
        case other => fail(s"Ожидался отказ в правах, получено: $other")
      }
  }

  test("tracker_app может работать с данными: права из default privileges действуют") {
    val email = s"priv-${java.util.UUID.randomUUID}@example.com"
    val cycle =
      for
        inserted <- sql"INSERT INTO users (email, password_hash) VALUES ($email, 'x')".update.run
        updated <- sql"UPDATE users SET password_hash = 'y' WHERE email = $email::citext".update.run
        selected <- sql"SELECT count(*) FROM users WHERE email = $email::citext"
          .query[Int]
          .unique
        deleted <- sql"DELETE FROM users WHERE email = $email::citext".update.run
      yield List(inserted, updated, selected, deleted)
    cycle.transact(xa()).assertEquals(List(1, 1, 1, 1))
  }

  test("tracker_app работает без привилегий роли и не владеет ни одной таблицей") {
    for
      flags <- scalar[(Boolean, Boolean, Boolean)](
        "SELECT rolsuper, rolcreatedb, rolcreaterole FROM pg_roles WHERE rolname = current_user"
      )
      owned <- scalar[Long](
        "SELECT count(*) FROM pg_tables WHERE tableowner = current_user"
      )
      truncate <- scalar[Boolean]("SELECT has_table_privilege('users', 'TRUNCATE')")
    yield
      assertEquals(flags, (false, false, false))
      assertEquals(owned, 0L)
      assertEquals(truncate, false)
  }

  test("все таблицы приложения принадлежат tracker_owner, и он не суперпользователь") {
    for
      owners <- scalar[Long](
        "SELECT count(*) FROM pg_tables WHERE schemaname = 'public' AND tableowner <> 'tracker_owner'"
      )
      isSuper <- scalar[Boolean]("SELECT rolsuper FROM pg_roles WHERE rolname = 'tracker_owner'")
    yield
      assertEquals(owners, 0L)
      assertEquals(isSuper, false)
  }

  test("приложение отказывается стартовать, если пул работает под суперпользователем") {
    for
      config <- TestDatabase.config
      superuser <- TestDatabase.superuser
      result <- Database.transactor(config.copy(app = superuser)).use_.attempt
    yield assert(result.left.exists(_.getMessage.contains("CREATE")), s"Получено: $result")
  }

  test("приложение отказывается стартовать, если пул работает под владельцем схемы") {
    for
      config <- TestDatabase.config
      migration <- TestDatabase.migrationConfig
      result <- Database.transactor(config.copy(app = migration.owner)).use_.attempt
    yield assert(result.left.exists(_.getMessage.contains("CREATE")), s"Получено: $result")
  }

  test("миграции идемпотентны: повторный запуск ничего не меняет") {
    TestDatabase.migrationConfig.flatMap(Database.migrate).attempt.map(r => assert(r.isRight, r))
  }

  test("неверный пароль владельца: режим migrate завершается ошибкой") {
    for
      migration <- TestDatabase.migrationConfig
      wrong = migration.copy(owner = migration.owner.copy(password = ciris.Secret("wrong")))
      result <- Database.migrate(wrong).attempt
    yield assert(result.isLeft)
  }

  test("сервер не стартует на базе без миграций и объясняет почему") {
    val database = s"empty_${java.util.UUID.randomUUID.toString.replace("-", "")}"
    def createDatabase(url: String, credentials: tracker.config.DbCredentials): IO[Unit] =
      IO.blocking {
        val connection = java.sql.DriverManager
          .getConnection(url, credentials.user, credentials.password.value)
        try connection.createStatement().execute(s"CREATE DATABASE $database")
        finally connection.close()
      }.void
    for
      config <- TestDatabase.config
      superuser <- TestDatabase.superuser
      _ <- createDatabase(config.url, superuser)
      empty = config.copy(url = config.url.replace("/tracker", s"/$database"))
      result <- Database.transactor(empty).use_.attempt
    yield assert(result.left.exists(_.getMessage.contains("миграции")), s"Получено: $result")
  }
