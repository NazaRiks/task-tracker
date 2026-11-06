package tracker.http

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.{AuthedRoutes, ContextRequest, HttpRoutes, Response, Status}
import tracker.auth.{AuthService, Authenticated}

/** Защищает группу маршрутов целиком: обработчик получает пользователя только из сессии, поэтому
  * забыть проверку или взять `user_id` из запроса нельзя.
  */
final class AuthGuard(auth: AuthService, cookies: SessionCookies):
  private val unauthorized: IO[Response[IO]] =
    ApiError.response(Status.Unauthorized, "unauthorized", "Требуется вход")

  def protect(routes: AuthedRoutes[Authenticated, IO]): HttpRoutes[IO] =
    Kleisli { request =>
      val session = cookies.tokenFrom(request) match
        case Some(token) => auth.authenticate(token)
        case None        => IO.pure(None) // без куки и с мусором в ней база не нужна

      OptionT.liftF(session).flatMap {
        case None                => OptionT.liftF(unauthorized)
        case Some(authenticated) =>
          routes(ContextRequest(authenticated, request)).map { response =>
            // База продлила сессию, значит продлеваем и куку в браузере. Если обработчик сам
            // поставил куку (выход), её не перезаписываем.
            if authenticated.renewed && response.cookies.isEmpty then
              response.addCookie(cookies.issue(authenticated.token))
            else response
          }
      }
    }
