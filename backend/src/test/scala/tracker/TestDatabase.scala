package tracker

import cats.effect.{IO, Resource}
import ciris.Secret
import doobie.Transactor
import org.testcontainers.postgresql.PostgreSQLContainer
import org.testcontainers.utility.{DockerImageName, MountableFile}
import tracker.config.{DbConfig, DbCredentials}
import tracker.db.Database

/** Настоящий Postgres той же версии, что и в docker-compose.yml, с тем же init-скриптом ролей.
  *
  * Контейнер один на все сьюты и запускается при первом обращении, остановит его Ryuk по завершении
  * JVM. Сьюты не мешают друг другу: у каждого теста свои случайные email.
  */
object TestDatabase:
  private val image = DockerImageName.parse("postgres:18.6-alpine")
  private val OwnerPassword = "test-owner-password"
  private val AppPassword = "test-app-password"

  private lazy val container: PostgreSQLContainer =
    val postgres = new PostgreSQLContainer(image)
      .withDatabaseName("tracker")
      .withEnv("DB_MIGRATION_PASSWORD", OwnerPassword)
      .withEnv("DB_PASSWORD", AppPassword)
      .withCopyFileToContainer(
        MountableFile.forHostPath("db-init/01-roles.sh", 493), // 0755
        "/docker-entrypoint-initdb.d/01-roles.sh"
      )
    postgres.start()
    postgres

  val config: IO[DbConfig] = IO.blocking {
    DbConfig(
      url = container.getJdbcUrl,
      migration = DbCredentials("tracker_owner", Secret(OwnerPassword)),
      app = DbCredentials("tracker_app", Secret(AppPassword)),
      poolSize = 4
    )
  }

  /** Суперпользователь контейнера: только чтобы проверить, что приложение откажется под ним
    * работать.
    */
  val superuser: IO[DbCredentials] = IO.blocking {
    DbCredentials(container.getUsername, Secret(container.getPassword))
  }

  /** Пул роли приложения. */
  val transactor: Resource[IO, Transactor[IO]] =
    Resource.eval(config).flatMap(Database.transactor)
