package tracker.http

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.server.Router
import org.http4s.{HttpApp, HttpRoutes, Status}
import org.typelevel.log4cats.Logger
import tracker.auth.{AuthService, RateLimiter, RateLimits}
import tracker.config.SecurityConfig
import tracker.habits.HabitService

import java.time.LocalDate

object Api:

  /** Все маршруты API. Порядок важен: `AuthGuard` отвечает 401 на всё, что до него дошло, поэтому
    * публичные маршруты стоят раньше, а защищённые смонтированы только под точными префиксами.
    * Остальные адреса (`/api/неизвестный`, а на этапе 5 и статика) мимо них проходят дальше по
    * цепочке и не требуют сессии.
    */
  def routes(
      auth: AuthService,
      habits: HabitService,
      limiter: RateLimiter,
      limits: RateLimits,
      security: SecurityConfig,
      checkDb: IO[Unit],
      utcToday: IO[LocalDate]
  )(using Logger[IO]): HttpRoutes[IO] =
    val cookies = SessionCookies(security.cookieSecure)
    val guard = AuthGuard(auth, cookies)
    val authRoutes = AuthRoutes(auth, limiter, limits, cookies, security.trustForwardedFor)
    val habitRoutes = HabitRoutes(habits, utcToday)

    HealthRoutes.routes(checkDb) <+>
      Router(
        "/api/auth" -> (authRoutes.public <+> guard.protect(authRoutes.authed)),
        "/api/habits" -> guard.protect(habitRoutes.habits),
        "/api/checkins" -> guard.protect(habitRoutes.checkins)
      )

  /** Всё приложение целиком: его же используют тесты, поэтому они проверяют настоящую цепочку. */
  def httpApp(
      auth: AuthService,
      habits: HabitService,
      limiter: RateLimiter,
      limits: RateLimits,
      security: SecurityConfig,
      checkDb: IO[Unit],
      utcToday: IO[LocalDate]
  )(using Logger[IO]): HttpApp[IO] =
    ServerMiddleware(security)(
      routes(auth, habits, limiter, limits, security, checkDb, utcToday).orNotFound
        .mapF(_.flatMap { response =>
          if response.status == Status.NotFound then ApiError.notFound
          else IO.pure(response)
        })
    )
