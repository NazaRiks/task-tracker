package tracker.http

import cats.effect.IO
import io.circe.Json
import org.http4s.HttpRoutes
import org.http4s.circe.*
import org.http4s.dsl.io.*
import org.typelevel.log4cats.Logger

import scala.concurrent.duration.*

object HealthRoutes:
  private val dbCheckTimeout = 2.seconds

  /** Проверка базы передаётся функцией, а не `Transactor`: маршрут не зависит от doobie, а отказ
    * базы легко проверить в тесте.
    *
    * Причина отказа пишется только в лог. Наружу этот публичный адрес её не раскрывает.
    */
  def routes(checkDb: IO[Unit])(using logger: Logger[IO]): HttpRoutes[IO] =
    HttpRoutes.of[IO] { case GET -> Root / "api" / "health" =>
      // Не `timeout`: он ждёт завершения отмены, а JDBC-вызов отменить нельзя, и ответ завис бы
      // до таймаута пула. Брошенный вызов завершится сам, его время ограничивает пул.
      checkDb.timeoutAndForget(dbCheckTimeout).attempt.flatMap {
        case Right(_) =>
          Ok(Json.obj("status" -> Json.fromString("ok")))
        case Left(error) =>
          logger.error(error)("Health check: база данных недоступна") *>
            ServiceUnavailable(
              Json.obj(
                "error" -> Json.fromString("db_unavailable"),
                "message" -> Json.fromString("База данных недоступна")
              )
            )
      }
    }
