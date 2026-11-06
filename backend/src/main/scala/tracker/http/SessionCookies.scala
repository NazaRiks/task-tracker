package tracker.http

import cats.effect.IO
import org.http4s.{Request, ResponseCookie, SameSite}
import tracker.auth.{AuthService, SessionToken}

import scala.concurrent.duration.FiniteDuration

/** С флагом Secure используется префикс `__Host-`: браузер примет такую куку только по HTTPS, без
  * Domain и с Path=/, поэтому её нельзя подставить с поддомена.
  */
final class SessionCookies(secure: Boolean):
  val name: String = if secure then "__Host-session" else "session"

  private def cookie(content: String, maxAge: Long): ResponseCookie =
    ResponseCookie(
      name = name,
      content = content,
      maxAge = Some(maxAge),
      path = Some("/"),
      secure = secure,
      httpOnly = true,
      sameSite = Some(SameSite.Lax)
    )

  def issue(token: SessionToken, ttl: FiniteDuration = AuthService.SessionTtl): ResponseCookie =
    cookie(token.value, ttl.toSeconds)

  val clear: ResponseCookie = cookie("", 0)

  def tokenFrom(request: Request[IO]): Option[SessionToken] =
    request.cookies.find(_.name == name).flatMap(cookie => SessionToken.parse(cookie.content))
