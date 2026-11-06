package tracker.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.{Decoder, Encoder}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.{AuthedRoutes, Header, HttpRoutes, Request, Response, Status}
import org.typelevel.ci.*
import tracker.auth.*

import java.util.UUID
import scala.concurrent.duration.FiniteDuration

object AuthRoutes:
  private given Decoder[Credentials] =
    Decoder.forProduct2("email", "password")(Credentials.apply)

  final private case class UserResponse(id: UUID, email: String) derives Encoder.AsObject

  private def userResponse(user: User): UserResponse = UserResponse(user.id, user.email)

final class AuthRoutes(
    auth: AuthService,
    limiter: RateLimiter,
    limits: RateLimits,
    cookies: SessionCookies,
    trustForwardedFor: Boolean
):
  import AuthRoutes.{*, given}

  val public: HttpRoutes[IO] = HttpRoutes.of[IO] {
    case request @ POST -> Root / "register" =>
      val ip = ClientIp.key(request, trustForwardedFor)
      limited(s"register|$ip", limits.registerPerIp) {
        request.as[Credentials].flatMap(auth.register).flatMap {
          case Right(session) =>
            revokePresented(request) *>
              Created(userResponse(session.user)).map(_.addCookie(cookies.issue(session.token)))
          case Left(RegisterError.Invalid(message)) =>
            ApiError.response(Status.BadRequest, "validation_failed", message)
          // Известное ограничение: так можно узнать, что email занят (см. SECURITY.md).
          case Left(RegisterError.EmailTaken) =>
            ApiError.response(Status.Conflict, "email_taken", "Этот email уже зарегистрирован")
        }
      }

    case request @ POST -> Root / "login" =>
      val ip = ClientIp.key(request, trustForwardedFor)
      request.as[Credentials].flatMap { credentials =>
        // Длина ключа ограничена: иначе он занимал бы в памяти лимитера до 16 KB.
        val email = Email.normalize(credentials.email).take(Email.MaxLength)
        limited(s"login-ip|$ip", limits.loginPerIp) {
          limited(s"login-ip-email|$ip|$email", limits.loginPerIpEmail) {
            auth.login(credentials).flatMap {
              case Some(session) =>
                revokePresented(request) *>
                  Ok(userResponse(session.user)).map(_.addCookie(cookies.issue(session.token)))
              case None =>
                ApiError.response(
                  Status.Unauthorized,
                  "invalid_credentials",
                  "Неверный email или пароль"
                )
            }
          }
        }
      }

    case request @ POST -> Root / "logout" =>
      revokePresented(request) *> NoContent().map(_.addCookie(cookies.clear))
  }

  val authed: AuthedRoutes[Authenticated, IO] = AuthedRoutes.of[Authenticated, IO] {
    case GET -> Root / "me" as authenticated =>
      Ok(userResponse(authenticated.user))

    case POST -> Root / "logout-all" as authenticated =>
      auth.logoutAll(authenticated.user.id) *> NoContent().map(_.addCookie(cookies.clear))
  }

  /** Старая сессия не должна оставаться живой, когда браузер получает новую. */
  private def revokePresented(request: Request[IO]): IO[Unit] =
    cookies.tokenFrom(request).traverse_(auth.logout)

  private def limited(key: String, rule: RateRule)(
      action: => IO[Response[IO]]
  ): IO[Response[IO]] =
    limiter.check(key, rule).flatMap {
      case RateDecision.Allowed             => action
      case RateDecision.Limited(retryAfter) => tooManyRequests(retryAfter)
    }

  private def tooManyRequests(retryAfter: FiniteDuration): IO[Response[IO]] =
    // Округление вверх: ответ «через 0 секунд» провоцировал бы немедленный повтор.
    val seconds = (retryAfter.toMillis + 999) / 1000
    ApiError
      .response(Status.TooManyRequests, "rate_limited", "Слишком много попыток, повторите позже")
      .map(_.putHeaders(Header.Raw(ci"Retry-After", seconds.toString)))
