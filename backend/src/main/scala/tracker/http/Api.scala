package tracker.http

import cats.effect.IO
import cats.syntax.all.*
import org.http4s.server.Router
import org.http4s.{HttpApp, Status}
import org.typelevel.log4cats.Logger
import tracker.auth.{AuthService, RateLimiter, RateLimits}
import tracker.config.SecurityConfig

object Api:

  /** Всё приложение целиком: его же используют тесты, поэтому они проверяют настоящую цепочку. */
  def httpApp(
      auth: AuthService,
      limiter: RateLimiter,
      limits: RateLimits,
      security: SecurityConfig,
      checkDb: IO[Unit]
  )(using Logger[IO]): HttpApp[IO] =
    val cookies = SessionCookies(security.cookieSecure)
    val guard = AuthGuard(auth, cookies)
    val authRoutes = AuthRoutes(auth, limiter, limits, cookies, security.trustForwardedFor)

    val routes =
      HealthRoutes.routes(checkDb) <+>
        Router("/api/auth" -> (authRoutes.public <+> guard.protect(authRoutes.authed)))

    ServerMiddleware(security)(
      routes.orNotFound.mapF(_.flatMap { response =>
        if response.status == Status.NotFound then
          ApiError.response(Status.NotFound, "not_found", "Не найдено")
        else IO.pure(response)
      })
    )
