package tracker

import cats.effect.{IO, IOApp, Resource}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import tracker.auth.{AuthService, Passwords, RateLimiter, RateLimits}
import tracker.config.AppConfig
import tracker.db.Database
import tracker.http.Api

import scala.concurrent.duration.*

object Main extends IOApp.Simple:
  private given logger: Logger[IO] = Slf4jLogger.getLogger[IO]

  private val SessionCleanupInterval = 1.hour
  private val LimiterPruneInterval = 5.minutes
  private val ShutdownTimeout = 5.seconds

  /** Повторяет задачу с паузой. Сбой записывается в лог и не останавливает следующие запуски. */
  private def repeatEvery(interval: FiniteDuration, name: String)(task: IO[Unit]): IO[Unit] =
    (IO.sleep(interval) *> task.handleErrorWith(
      logger.error(_)(s"Сбой фоновой задачи: $name")
    )).foreverM

  /** Порядок ресурсов задаёт и порядок остановки: сначала сервер прекращает приём запросов, потом
    * останавливаются фоновые задачи и закрывается пул соединений с базой.
    */
  private val server: Resource[IO, Unit] =
    for
      config <- Resource.eval(AppConfig.load)
      xa <- Database.transactor(config.db)
      passwords <- Resource.eval(Passwords.create)
      limiter <- Resource.eval(RateLimiter.create())
      auth = AuthService(xa, passwords)
      _ <- repeatEvery(SessionCleanupInterval, "очистка сессий") {
        auth.deleteExpiredSessions.flatMap(count =>
          logger.info(s"Удалено просроченных сессий: $count")
        )
      }.background
      _ <- repeatEvery(LimiterPruneInterval, "очистка лимитов")(limiter.prune).background
      app = Api.httpApp(
        auth,
        limiter,
        RateLimits.default,
        config.security,
        Database.ping(xa)
      )
      server <- EmberServerBuilder
        .default[IO]
        .withHost(config.http.host)
        .withPort(config.http.port)
        .withHttpApp(app)
        // По умолчанию Ember до 30 секунд ждёт закрытия keep-alive соединений браузеров, а Docker
        // через 10 секунд убивает процесс, и ресурсы не успели бы закрыться по порядку.
        .withShutdownTimeout(ShutdownTimeout)
        .build
      _ <- Resource.eval(logger.info(s"Сервер запущен: ${server.baseUri}"))
    yield ()

  val run: IO[Unit] = server.useForever
