package tracker

import cats.effect.{ExitCode, IO, IOApp, Resource}
import org.http4s.ember.server.EmberServerBuilder
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.slf4j.Slf4jLogger
import tracker.auth.{AuthService, Passwords, PasswordPolicy, RateLimiter, RateLimits}
import tracker.config.{AppConfig, MigrationConfig}
import tracker.db.Database
import tracker.habits.{HabitRules, HabitService}
import tracker.http.Api

import scala.concurrent.duration.*

/** Один образ, два режима. Без аргументов — сервер, он знает только роль приложения. С аргументом
  * `migrate` — миграции под владельцем схемы и выход. Режимы читают разные переменные окружения,
  * поэтому пароль владельца не попадает в окружение работающего сервера.
  */
object Main extends IOApp:
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
      passwordPolicy <- Resource.eval(PasswordPolicy.load)
      limiter <- Resource.eval(RateLimiter.create())
      auth = AuthService(xa, passwords, passwordPolicy)
      _ <- repeatEvery(SessionCleanupInterval, "очистка сессий") {
        auth.deleteExpiredSessions.flatMap(count =>
          logger.info(s"Удалено просроченных сессий: $count")
        )
      }.background
      _ <- repeatEvery(LimiterPruneInterval, "очистка лимитов")(limiter.prune).background
      app = Api.httpApp(
        auth,
        HabitService(xa),
        limiter,
        RateLimits.default,
        config.security,
        Database.ping(xa),
        HabitRules.utcToday
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

  /** Падение миграции даёт ненулевой код выхода, и compose не запускает сервер. */
  private val migrate: IO[ExitCode] =
    MigrationConfig.load.flatMap(Database.migrate) *>
      logger.info("Миграции применены").as(ExitCode.Success)

  def run(args: List[String]): IO[ExitCode] =
    args match
      case Nil             => server.useForever
      case List("migrate") => migrate
      case _               =>
        logger.error("Неизвестный режим запуска: допустим только аргумент migrate").as(ExitCode(2))
