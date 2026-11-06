package tracker.db

import cats.effect.{IO, Resource}
import cats.syntax.all.*
import com.zaxxer.hikari.HikariConfig
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import org.flywaydb.core.Flyway
import tracker.config.DbConfig

object Database:

  /** Пул соединений роли приложения с уже применёнными миграциями. Если миграция упала или роль
    * приложения имеет лишние права, ресурс не создаётся и приложение не стартует.
    */
  def transactor(config: DbConfig): Resource[IO, Transactor[IO]] =
    Resource.eval(migrate(config)) *>
      HikariTransactor
        .fromHikariConfig[IO](hikariConfig(config))
        .evalTap(ensureLeastPrivilege)

  /** Минимальный запрос для health-check: подтверждает, что база реально отвечает. */
  def ping(xa: Transactor[IO]): IO[Unit] =
    sql"SELECT 1".query[Int].unique.transact(xa).void

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

  /** Отдельное короткое соединение владельца схемы: в пул эта роль не попадает. */
  private def migrate(config: DbConfig): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(config.url, config.migration.user, config.migration.password.value)
        .locations("classpath:db/migration")
        .failOnMissingLocations(true)
        .load()
        .migrate()
    }.void

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
