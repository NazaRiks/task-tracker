package tracker.http

import cats.data.{Kleisli, OptionT}
import cats.effect.IO
import org.http4s.{AuthedRoutes, ContextRequest, HttpRoutes, Response, Status}
import tracker.auth.{AuthService, Authenticated}

/** Защищает группу маршрутов целиком: обработчик получает пользователя только из сессии, поэтому
  * забыть проверку или взять `user_id` из запроса нельзя.
  *
  * Без сессии ответ 401 приходит на ЛЮБОЙ запрос, дошедший до защищённых маршрутов, и следующие
  * маршруты в цепочке `<+>` уже не вызываются. Поэтому монтировать их можно только под конкретным
  * префиксом и только через `Api.routes`, иначе они перекроют публичные маршруты и статику.
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
            authenticated.renewedFor match
              case Some(ttl) if response.cookies.isEmpty =>
                response.addCookie(cookies.issue(authenticated.token, ttl))
              case _ => response
          }
      }
    }
