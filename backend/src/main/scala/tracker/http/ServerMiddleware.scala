package tracker.http

import cats.data.Kleisli
import cats.effect.IO
import org.http4s.*
import org.http4s.server.middleware.EntityLimiter
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import tracker.config.SecurityConfig

object ServerMiddleware:
  val MaxBodyBytes: Long = 16 * 1024

  val ContentSecurityPolicy: String =
    "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
      "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; " +
      "form-action 'self'"

  private val PermissionsPolicy = "camera=(), microphone=(), geolocation=(), payment=(), usb=()"
  private val Hsts = "max-age=31536000"

  private val safeMethods: Set[Method] = Set(Method.GET, Method.HEAD, Method.OPTIONS)

  /** Порядок важен. Заголовки снаружи, чтобы попасть и в ответы с ошибками. CSRF раньше
    * обработчиков и раньше чтения тела: отклонённый запрос не тратит ни базу, ни память.
    */
  def apply(security: SecurityConfig)(app: HttpApp[IO])(using Logger[IO]): HttpApp[IO] =
    securityHeaders(security)(errorHandling(csrf(security)(EntityLimiter(app, MaxBodyBytes))))

  private def securityHeaders(security: SecurityConfig)(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli { request =>
      app(request).map { response =>
        val base = response.putHeaders(
          Header.Raw(ci"Content-Security-Policy", ContentSecurityPolicy),
          Header.Raw(ci"X-Content-Type-Options", "nosniff"),
          Header.Raw(ci"Referrer-Policy", "strict-origin-when-cross-origin"),
          Header.Raw(ci"Permissions-Policy", PermissionsPolicy)
        )
        val withHsts =
          if security.cookieSecure then
            base.putHeaders(Header.Raw(ci"Strict-Transport-Security", Hsts))
          else base
        // Ответы API содержат личные данные: их не должны сохранять браузер и прокси.
        if request.uri.path.renderString.startsWith("/api/") then
          withHsts.putHeaders(Header.Raw(ci"Cache-Control", "no-store"))
        else withHsts
      }
    }

  /** Защита от CSRF в дополнение к SameSite=Lax:
    *   - кросс-доменная форма не может отправить `application/json` без preflight, а CORS выключен;
    *   - Origin браузер проставляет сам, страница не может его подделать.
    *
    * Запрос без Origin отклоняется: все современные браузеры отправляют его при изменяющих методах.
    */
  private def csrf(security: SecurityConfig)(app: HttpApp[IO]): HttpApp[IO] =
    Kleisli { request =>
      if safeMethods.contains(request.method) then app(request)
      else
        val originMatches = request.headers.get(ci"Origin").exists { values =>
          values.length == 1 && values.head.value == security.siteOrigin
        }
        val isJson = request.contentType.exists(_.mediaType == MediaType.application.json)
        if originMatches && isJson then app(request)
        else ApiError.response(Status.Forbidden, "csrf_rejected", "Запрос отклонён")
    }

  /** Единая точка для исключений. Наружу уходят только код и request id, подробности — в лог.
    * Заголовки и тело не логируются: в них могут быть куки и пароли.
    */
  private def errorHandling(app: HttpApp[IO])(using logger: Logger[IO]): HttpApp[IO] =
    Kleisli { request =>
      IO.randomUUID.map(_.toString).flatMap { requestId =>
        app(request)
          .handleErrorWith {
            case _: EntityLimiter.EntityTooLarge =>
              ApiError.response(
                Status.PayloadTooLarge,
                "body_too_large",
                "Слишком большое тело запроса"
              )
            case _: MessageFailure =>
              ApiError.response(
                Status.BadRequest,
                "invalid_request",
                "Некорректное тело запроса"
              )
            case error =>
              logger.error(error)(
                s"Ошибка обработки запроса requestId=$requestId " +
                  s"${request.method.name} ${request.uri.path.renderString}"
              ) *> ApiError.response(
                Status.InternalServerError,
                "internal_error",
                s"Внутренняя ошибка сервера. Код запроса: $requestId"
              )
          }
          .map(_.putHeaders(Header.Raw(ci"X-Request-Id", requestId)))
      }
    }
