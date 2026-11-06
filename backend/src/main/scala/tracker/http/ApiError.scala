package tracker.http

import cats.effect.IO
import io.circe.Json
import org.http4s.circe.*
import org.http4s.{Response, Status}

/** Единый формат всех ошибок API: `{"error": код, "message": текст для пользователя}`. */
object ApiError:
  def response(status: Status, code: String, message: String): IO[Response[IO]] =
    IO.pure(
      Response[IO](status).withEntity(
        Json.obj(
          "error" -> Json.fromString(code),
          "message" -> Json.fromString(message)
        )
      )
    )
