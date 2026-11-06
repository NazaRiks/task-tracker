package tracker

import cats.effect.{IO, IOApp, Resource}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import tracker.config.AppConfig
import tracker.db.Database
import tracker.http.HealthRoutes

object Main extends IOApp.Simple:
  private given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  /** Порядок ресурсов задаёт и порядок остановки: сначала сервер прекращает приём запросов, потом
    * закрывается пул соединений с базой.
    */
  private val server: Resource[IO, Unit] =
    for
      config <- Resource.eval(AppConfig.load)
      xa <- Database.transactor(config.db)
      routes = HealthRoutes.routes(Database.ping(xa))
      server <- EmberServerBuilder
        .default[IO]
        .withHost(config.http.host)
        .withPort(config.http.port)
        .withHttpApp(routes.orNotFound)
        .build
      _ <- Resource.eval(logger.info(s"Сервер запущен: ${server.baseUri}"))
    yield ()

  val run: IO[Unit] = server.useForever
