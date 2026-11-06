package tracker.http

import cats.effect.IO
import cats.syntax.all.*
import io.circe.syntax.*
import io.circe.{Decoder, Encoder, JsonObject}
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.dsl.io.*
import org.http4s.{AuthedRoutes, Response}
import tracker.auth.Authenticated
import tracker.habits.*

import java.time.LocalDate

object HabitRoutes:
  private given Encoder.AsObject[Habit] =
    Encoder.forProduct6("id", "name", "color", "position", "start_date", "created_at") { h =>
      (h.id, h.name, h.color, h.position, h.startDate, h.createdAt)
    }

  private given Encoder.AsObject[HabitWithStreaks] = Encoder.AsObject.instance { h =>
    JsonObject.fromIterable(
      h.habit.asJsonObject.toIterable ++ List("streak" -> h.streak.asJson, "best" -> h.best.asJson)
    )
  }

  final private case class CreateBody(name: String, color: String, startDate: String)
  private given Decoder[CreateBody] =
    Decoder.forProduct3("name", "color", "start_date")(CreateBody.apply)

  final private case class PatchBody(
      name: Option[String],
      color: Option[String],
      startDate: Option[String]
  )
  private given Decoder[PatchBody] =
    Decoder.forProduct3("name", "color", "start_date")(PatchBody.apply)

  final private case class StarterBody(startDate: String)
  private given Decoder[StarterBody] =
    Decoder.forProduct1("start_date")(StarterBody.apply)

  final private case class OrderBody(ids: List[java.util.UUID])
  private given Decoder[OrderBody] = Decoder.forProduct1("ids")(OrderBody.apply)

/** Пользователь берётся только из сессии: `AuthGuard` передаёт его через `as authenticated`, в теле
  * и параметрах запроса `user_id` не читается нигде.
  *
  * Проверка данных идёт раньше обращения к базе, поэтому ответ 400 одинаков для своей и чужой
  * задачи.
  */
final class HabitRoutes(service: HabitService, utcToday: IO[LocalDate]):
  import HabitRoutes.{*, given}

  private def validated[A](value: Either[String, A])(
      action: A => IO[Response[IO]]
  ): IO[Response[IO]] =
    value.fold(ApiError.validation, action)

  private def orNotFound(found: Boolean): IO[Response[IO]] =
    if found then NoContent() else ApiError.notFound

  /** Монтируются под `/api/habits`. */
  val habits: AuthedRoutes[Authenticated, IO] = AuthedRoutes.of[Authenticated, IO] {
    case context @ GET -> Root as authenticated =>
      utcToday.flatMap { today =>
        validated(HabitRules.clientToday(context.req.params.get("today"), today)) { clientToday =>
          service.list(authenticated.user.id, clientToday).flatMap(Ok(_))
        }
      }

    case context @ POST -> Root as authenticated =>
      for
        body <- context.req.as[CreateBody]
        today <- utcToday
        input = (
          HabitRules.name(body.name),
          HabitRules.color(body.color),
          HabitRules.startDate(body.startDate, today)
        ).tupled
        response <- validated(input) { (name, color, startDate) =>
          service.create(authenticated.user.id, name, color, startDate).flatMap(Created(_))
        }
      yield response

    case context @ POST -> Root / "starter" as authenticated =>
      for
        body <- context.req.as[StarterBody]
        today <- utcToday
        response <- validated(HabitRules.startDate(body.startDate, today)) { startDate =>
          service.createStarter(authenticated.user.id, startDate).flatMap {
            case Some(created) => Created(created)
            case None          =>
              ApiError.response(
                Conflict,
                "habits_exist",
                "Стартовый набор только для пустого списка"
              )
          }
        }
      yield response

    case context @ PUT -> Root / "order" as authenticated =>
      context.req.as[OrderBody].flatMap { body =>
        service.reorder(authenticated.user.id, body.ids).flatMap {
          case true  => NoContent()
          case false => ApiError.validation("Список id должен совпадать с задачами пользователя")
        }
      }

    case context @ PATCH -> Root / UUIDVar(habitId) as authenticated =>
      for
        body <- context.req.as[PatchBody]
        today <- utcToday
        input = (
          body.name.traverse(HabitRules.name),
          body.color.traverse(HabitRules.color),
          body.startDate.traverse(HabitRules.startDate(_, today))
        ).mapN(HabitPatch.apply)
          .filterOrElse(
            patch => patch.name.isDefined || patch.color.isDefined || patch.startDate.isDefined,
            "Нет полей для изменения"
          )
        response <- validated(input) { patch =>
          service.update(authenticated.user.id, habitId, patch).flatMap {
            case Some(habit) => Ok(habit)
            case None        => ApiError.notFound
          }
        }
      yield response

    case DELETE -> Root / UUIDVar(habitId) as authenticated =>
      service.delete(authenticated.user.id, habitId).flatMap(orNotFound)

    case PUT -> Root / UUIDVar(habitId) / "checkins" / rawDay as authenticated =>
      utcToday.flatMap { today =>
        validated(HabitRules.checkinDay(rawDay, today)) { day =>
          service.addCheckin(authenticated.user.id, habitId, day).flatMap(orNotFound)
        }
      }

    case DELETE -> Root / UUIDVar(habitId) / "checkins" / rawDay as authenticated =>
      utcToday.flatMap { today =>
        validated(HabitRules.checkinDay(rawDay, today)) { day =>
          service.removeCheckin(authenticated.user.id, habitId, day).flatMap(orNotFound)
        }
      }
  }

  /** Монтируется под `/api/checkins`. */
  val checkins: AuthedRoutes[Authenticated, IO] = AuthedRoutes.of[Authenticated, IO] {
    case context @ GET -> Root as authenticated =>
      val params = context.req.params
      validated(HabitRules.range(params.get("from"), params.get("to"))) { (from, to) =>
        service.checkins(authenticated.user.id, from, to).flatMap(Ok(_))
      }
  }
