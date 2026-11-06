package tracker

import cats.effect.{IO, Resource}
import ciris.Secret
import doobie.Transactor
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.DockerImageName
import tracker.config.DbConfig
import tracker.db.Database

/** Настоящий Postgres той же версии, что и в docker-compose.yml, с применёнными миграциями. */
object TestDatabase:
  private val image = DockerImageName.parse("postgres:18.6-alpine")

  val transactor: Resource[IO, Transactor[IO]] =
    Resource
      .make(IO.blocking {
        val container = new PostgreSQLContainer(image)
        container.start()
        container
      })(container => IO.blocking(container.stop()))
      .flatMap { container =>
        Database.transactor(
          DbConfig(
            url = container.getJdbcUrl,
            user = container.getUsername,
            password = Secret(container.getPassword),
            poolSize = 4
          )
        )
      }
