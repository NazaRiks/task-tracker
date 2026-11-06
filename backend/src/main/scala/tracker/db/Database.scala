package tracker.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.HikariConfig
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.log.LogHandler
import org.flywaydb.core.Flyway
import tracker.config.{DbConfig, MigrationConfig}

object Database:

  /** Таблицы, без которых сервер не может работать. */
  private val RequiredTables = Array("users", "sessions", "habits", "checkins")

  /** Пул соединений роли приложения. Миграции здесь не запускаются: их выполняет отдельный режим
    * под другой ролью. Если права лишние или схема не готова, ресурс не создаётся и сервер не
    * стартует.
    *
    * @param logHandler
    *   нужен только тестам, которые считают SQL-запросы; в рабочем режиме запросы не логируются
    */
  def transactor(
      config: DbConfig,
      logHandler: Option[LogHandler[IO]] = None
  ): Resource[IO, Transactor[IO]] =
    HikariTransactor
      .fromHikariConfig[IO](hikariConfig(config), logHandler)
      .evalTap(xa => ensureLeastPrivilege(xa) *> ensureSchemaReady(xa))

  /** Минимальный запрос для health-check: подтверждает, что база реально отвечает. */
  def ping(xa: Transactor[IO]): IO[Unit] =
    sql"SELECT 1".query[Int].unique.transact(xa).void

  /** Одно короткое соединение владельца схемы. Вызывается только в режиме `migrate`. */
  def migrate(config: MigrationConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.url, config.owner.user, config.owner.password.value)
        .locations("classpath:db/migration")
        .failOnMissingLocations(true)
        .load()
        .migrate()
    }.void

  private def hikariConfig(config: DbConfig): HikariConfig =
    val hikari = new HikariConfig()
    hikari.setDriverClassName("org.postgresql.Driver")
    hikari.setJdbcUrl(config.url)
    hikari.setUsername(config.app.user)
    hikari.setPassword(config.app.password.value)
    hikari.setMaximumPoolSize(config.poolSize)
    // Вместо стандартных 30 секунд: при отказе базы запрос быстро получает ошибку, а не висит.
    hikari.setConnectionTimeout(5000)
    hikari

  /** Защита от ошибки в настройке: право CREATE на схему есть и у суперпользователя, и у владельца
    * базы, а у `tracker_app` его быть не должно. Лучше не запуститься, чем молча работать с лишними
    * правами.
    */
  private def ensureLeastPrivilege(xa: Transactor[IO]): IO[Unit] =
    sql"SELECT has_schema_privilege(current_user, 'public', 'CREATE')"
      .query[Boolean]
      .unique
      .transact(xa)
      .flatMap { canCreate =>
        IO.raiseWhen(canCreate)(
          new IllegalStateException(
            "DB_USER имеет право CREATE на схему public: нужна роль tracker_app с минимальными правами"
          )
        )
      }

  /** Сервер запустили без миграций: понятная ошибка при старте вместо 500 на каждый запрос. Историю
    * миграций проверить нельзя, она закрыта от этой роли, поэтому проверяются сами таблицы.
    */
  private def ensureSchemaReady(xa: Transactor[IO]): IO[Unit] =
    sql"""
      SELECT count(*) FROM unnest($RequiredTables::text[]) AS t(name)
      WHERE to_regclass('public.' || t.name) IS NULL
    """
      .query[Int]
      .unique
      .transact(xa)
      .flatMap { missing =>
        IO.raiseWhen(missing > 0)(
          new IllegalStateException(
            "Схема базы не готова: сначала выполните миграции (режим migrate)"
          )
        )
      }
