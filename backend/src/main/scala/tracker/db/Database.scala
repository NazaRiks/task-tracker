package tracker.db

import cats.effect.{IO, Resource}
import com.zaxxer.hikari.{HikariConfig, HikariDataSource}
import doobie.Transactor
import doobie.hikari.HikariTransactor
import doobie.implicits.*
import org.flywaydb.core.Flyway
import tracker.config.DbConfig

object Database:

  /** Пул соединений с уже применёнными миграциями: получив `Transactor`, код может рассчитывать на
    * актуальную схему. Если миграция упала, ресурс не создаётся и приложение не стартует.
    */
  def transactor(config: DbConfig): Resource[IO, Transactor[IO]] =
    HikariTransactor
      .fromHikariConfig[IO](hikariConfig(config))
      .evalTap(xa => migrate(xa.kernel))

  /** Минимальный запрос для health-check: подтверждает, что база реально отвечает. */
  def ping(xa: Transactor[IO]): IO[Unit] =
    sql"SELECT 1".query[Int].unique.transact(xa).void

  private def hikariConfig(config: DbConfig): HikariConfig =
    val hikari = new HikariConfig()
    hikari.setDriverClassName("org.postgresql.Driver")
    hikari.setJdbcUrl(config.url)
    hikari.setUsername(config.user)
    hikari.setPassword(config.password.value)
    hikari.setMaximumPoolSize(config.poolSize)
    // Вместо стандартных 30 секунд: при отказе базы запрос быстро получает ошибку, а не висит.
    hikari.setConnectionTimeout(5000)
    hikari

  private def migrate(dataSource: HikariDataSource): IO[Unit] =
    IO.blocking {
      Flyway
        .configure()
        .dataSource(dataSource)
        .locations("classpath:db/migration")
        .failOnMissingLocations(true)
        .load()
        .migrate()
    }.void
