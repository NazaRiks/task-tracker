package tracker

import cats.effect.{IO, Ref, Resource}
import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import doobie.util.log.{LogEvent, LogHandler}
import io.circe.Json
import io.circe.syntax.*
import munit.AnyFixture
import munit.catseffect.IOFixture
import org.http4s.*
import org.http4s.circe.CirceEntityCodec.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import tracker.auth.*
import tracker.config.SecurityConfig
import tracker.habits.{HabitRepository, HabitService}
import tracker.http.Api

import java.time.LocalDate
import java.util.UUID
import scala.concurrent.duration.*

class HabitSpec extends munit.CatsEffectSuite:
  private given Logger[IO] = NoOpLogger[IO]

  /** Пул со счётчиком SQL-запросов: LogHandler doobie вызывается на каждый выполненный запрос. */
  private val db: IOFixture[(Transactor[IO], Ref[IO, List[String]])] =
    ResourceSuiteLocalFixture(
      "postgres",
      Resource.eval(Ref[IO].of(List.empty[String])).flatMap { statements =>
        val handler = new LogHandler[IO]:
          def run(event: LogEvent): IO[Unit] = statements.update(event.sql :: _)
        TestDatabase.transactorWith(Some(handler)).map(_ -> statements)
      }
    )

  override def munitFixtures: Seq[AnyFixture[?]] = List(db)

  private def xa: Transactor[IO] = db()._1

  private val Site = "https://tracker.test"
  private val CookieName = "__Host-session"
  private val Today = LocalDate.of(2026, 6, 15)

  /** Часы зафиксированы: граничные значения не зависят от времени запуска теста. */
  private lazy val app: IO[HttpApp[IO]] =
    for
      passwords <- Passwords.create
      policy <- PasswordPolicy.load
      limiter <- RateLimiter.create()
    yield Api.httpApp(
      AuthService(xa, passwords, policy),
      HabitService(xa),
      limiter,
      RateLimits(RateRule(100000, 1.hour), RateRule(100000, 1.hour), RateRule(100000, 1.hour)),
      SecurityConfig(Site, cookieSecure = true, trustForwardedFor = false),
      IO.unit,
      IO.pure(Today)
    )

  private def send(request: Request[IO]): IO[Response[IO]] = app.flatMap(_.run(request))

  private def request(
      method: Method,
      path: String,
      token: Option[String],
      body: Option[Json] = None,
      origin: Option[String] = Some(Site),
      json: Boolean = true
  ): Request[IO] =
    val uri = Uri.unsafeFromString(path)
    val base = body.fold(Request[IO](method, uri))(Request[IO](method, uri).withEntity(_))
    val typed =
      if json then base.withContentType(`Content-Type`(MediaType.application.json))
      else base.removeHeader[`Content-Type`]
    val withOrigin = origin.fold(typed)(o => typed.putHeaders(Header.Raw(ci"Origin", o)))
    token.fold(withOrigin)(withOrigin.addCookie(CookieName, _))

  private def get(path: String, token: String): IO[Response[IO]] =
    send(request(Method.GET, path, Some(token), origin = None, json = false))

  private def post(path: String, token: String, body: Json): IO[Response[IO]] =
    send(request(Method.POST, path, Some(token), Some(body)))

  private def patch(path: String, token: String, body: Json): IO[Response[IO]] =
    send(request(Method.PATCH, path, Some(token), Some(body)))

  private def put(path: String, token: String, body: Option[Json] = None): IO[Response[IO]] =
    send(request(Method.PUT, path, Some(token), body))

  private def delete(path: String, token: String): IO[Response[IO]] =
    send(request(Method.DELETE, path, Some(token)))

  private def signUp: IO[String] =
    val body = Json.obj(
      "email" -> s"habits-${UUID.randomUUID}@example.com".asJson,
      "password" -> "correct horse battery".asJson
    )
    send(request(Method.POST, "/api/auth/register", None, Some(body))).map { response =>
      assertEquals(response.status, Status.Created)
      response.cookies.find(_.name == CookieName).getOrElse(fail("Нет куки")).content
    }

  private def habitBody(
      name: String = "Зал",
      color: String = "green",
      start: String = "2026-06-01"
  ): Json =
    Json.obj("name" -> name.asJson, "color" -> color.asJson, "start_date" -> start.asJson)

  private def createHabit(token: String, name: String = "Зал"): IO[UUID] =
    post("/api/habits", token, habitBody(name)).flatMap { response =>
      assertEquals(response.status, Status.Created)
      response.as[Json].map(field[UUID](_, "id"))
    }

  private def field[A: io.circe.Decoder](json: Json, name: String): A =
    json.hcursor.get[A](name).getOrElse(fail(s"Нет поля $name в ${json.noSpaces}"))

  private def listHabits(token: String, today: LocalDate = Today): IO[List[Json]] =
    get(s"/api/habits?today=$today", token).flatMap { response =>
      assertEquals(response.status, Status.Ok)
      response.as[List[Json]]
    }

  private def checkinRows(habitId: UUID): IO[Int] =
    sql"SELECT count(*) FROM checkins WHERE habit_id = $habitId".query[Int].unique.transact(xa)

  private def errorCode(response: Response[IO]): IO[String] =
    response.as[Json].map(field[String](_, "error"))

  // ---------- Основные сценарии ----------

  test("создание, список, частичное изменение и удаление задачи") {
    for
      token <- signUp
      created <- post("/api/habits", token, habitBody("  Английский  ", "violet"))
      createdBody <- created.as[Json]
      id = field[UUID](createdBody, "id")
      second <- createHabit(token, "Зал")
      listed <- listHabits(token)
      renamed <- patch(s"/api/habits/$id", token, Json.obj("name" -> "Словенский".asJson))
      renamedBody <- renamed.as[Json]
      moved <- patch(s"/api/habits/$id", token, Json.obj("start_date" -> "2026-01-01".asJson))
      movedBody <- moved.as[Json]
      removed <- delete(s"/api/habits/$id", token)
      afterDelete <- listHabits(token)
    yield
      assertEquals(created.status, Status.Created)
      assertEquals(field[String](createdBody, "name"), "Английский")
      assertEquals(field[Int](createdBody, "position"), 0)
      assertEquals(listed.map(field[UUID](_, "id")), List(id, second))
      assertEquals(listed.map(field[Int](_, "position")), List(0, 1))
      assertEquals(field[Int](listed.head, "streak"), 0)
      assertEquals(field[Int](listed.head, "best"), 0)
      assertEquals(renamed.status, Status.Ok)
      assertEquals(field[String](renamedBody, "name"), "Словенский")
      assertEquals(field[String](renamedBody, "color"), "violet", "Остальные поля не меняются")
      assertEquals(field[String](renamedBody, "start_date"), "2026-06-01")
      assertEquals(field[String](movedBody, "start_date"), "2026-01-01")
      assertEquals(field[String](movedBody, "name"), "Словенский")
      assertEquals(removed.status, Status.NoContent)
      assertEquals(afterDelete.map(field[UUID](_, "id")), List(second))
  }

  test("отметки идемпотентны: повторный PUT и DELETE дают 204, а не 404") {
    for
      token <- signUp
      id <- createHabit(token)
      first <- put(s"/api/habits/$id/checkins/2026-06-15", token)
      again <- put(s"/api/habits/$id/checkins/2026-06-15", token)
      rows <- checkinRows(id)
      removed <- delete(s"/api/habits/$id/checkins/2026-06-15", token)
      removedAgain <- delete(s"/api/habits/$id/checkins/2026-06-15", token)
      left <- checkinRows(id)
    yield
      assertEquals(first.status, Status.NoContent)
      assertEquals(again.status, Status.NoContent)
      assertEquals(rows, 1)
      assertEquals(removed.status, Status.NoContent)
      assertEquals(removedAgain.status, Status.NoContent)
      assertEquals(left, 0)
  }

  test("GET /api/checkins: только период и только свои, с группировкой по задачам") {
    for
      token <- signUp
      a <- createHabit(token, "A")
      b <- createHabit(token, "B")
      empty <- createHabit(token, "Без отметок")
      _ <- List(
        s"$a/checkins/2026-06-14",
        s"$a/checkins/2026-06-10",
        s"$b/checkins/2026-06-12",
        s"$b/checkins/2026-05-31"
      ).traverse(path => put(s"/api/habits/$path", token))
      other <- signUp
      foreign <- createHabit(other)
      _ <- put(s"/api/habits/$foreign/checkins/2026-06-12", other)
      response <- get("/api/checkins?from=2026-06-01&to=2026-06-30", token)
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(
        body,
        Json.obj(
          a.toString -> List("2026-06-10", "2026-06-14").asJson,
          b.toString -> List("2026-06-12").asJson
        )
      )
      assert(body.hcursor.downField(empty.toString).failed)
  }

  test("стартовый набор: четыре задачи по порядку, повторно не добавляется") {
    val body = Json.obj("start_date" -> "2026-06-15".asJson)
    for
      token <- signUp
      first <- post("/api/habits/starter", token, body)
      created <- first.as[List[Json]]
      second <- post("/api/habits/starter", token, body)
      code <- errorCode(second)
      listed <- listHabits(token)
    yield
      assertEquals(first.status, Status.Created)
      assertEquals(
        created.map(field[String](_, "name")),
        List("Программирование", "Словенский", "Английский", "Зал")
      )
      assertEquals(created.map(field[Int](_, "position")), List(0, 1, 2, 3))
      assertEquals(second.status, Status.Conflict)
      assertEquals(code, "habits_exist")
      assertEquals(listed.length, 4)
  }

  test("PUT /api/habits/order меняет порядок") {
    for
      token <- signUp
      a <- createHabit(token, "A")
      b <- createHabit(token, "B")
      c <- createHabit(token, "C")
      response <- put("/api/habits/order", token, Some(Json.obj("ids" -> List(c, a, b).asJson)))
      listed <- listHabits(token)
    yield
      assertEquals(response.status, Status.NoContent)
      assertEquals(listed.map(field[UUID](_, "id")), List(c, a, b))
      assertEquals(listed.map(field[Int](_, "position")), List(0, 1, 2))
  }

  test("удаление задачи удаляет её отметки") {
    for
      token <- signUp
      id <- createHabit(token)
      _ <- put(s"/api/habits/$id/checkins/2026-06-15", token)
      _ <- delete(s"/api/habits/$id", token)
      left <- checkinRows(id)
    yield assertEquals(left, 0)
  }

  test("параллельные отметка и удаление задачи не дают 500") {
    for
      token <- signUp
      id <- createHabit(token)
      days = (1 to 15).toList.map(day => f"2026-06-$day%02d")
      results <- (
        days.parTraverse(day => put(s"/api/habits/$id/checkins/$day", token)),
        delete(s"/api/habits/$id", token)
      ).parTupled
      left <- checkinRows(id)
    yield
      val statuses = results._1.map(_.status).toSet
      assert(statuses.subsetOf(Set(Status.NoContent, Status.NotFound)), s"Статусы: $statuses")
      assertEquals(results._2.status, Status.NoContent)
      assertEquals(left, 0, "Отметка не должна пережить задачу")
  }

  // ---------- IDOR ----------

  /** Две пары: задача `victim` с отметкой у владельца и пользователь `attacker`. */
  private def victimAndAttacker: IO[(String, UUID, String)] =
    for
      owner <- signUp
      id <- createHabit(owner, "Личная задача")
      _ <- put(s"/api/habits/$id/checkins/2026-06-14", owner)
      attacker <- signUp
    yield (owner, id, attacker)

  test("IDOR: чужая задача неотличима от несуществующей, и ничего не меняется") {
    for
      (owner, id, attacker) <- victimAndAttacker
      missing = UUID.randomUUID
      attempts = List[UUID => IO[Response[IO]]](
        target => patch(s"/api/habits/$target", attacker, Json.obj("name" -> "взлом".asJson)),
        target => delete(s"/api/habits/$target", attacker),
        target => put(s"/api/habits/$target/checkins/2026-06-15", attacker),
        target => delete(s"/api/habits/$target/checkins/2026-06-14", attacker)
      )
      foreign <- attempts.traverse(_(id))
      foreignBodies <- foreign.traverse(_.as[Json])
      absent <- attempts.traverse(_(missing))
      absentBodies <- absent.traverse(_.as[Json])
      listed <- listHabits(owner)
      days <- get("/api/checkins?from=2026-06-01&to=2026-06-30", owner).flatMap(_.as[Json])
      rows <- checkinRows(id)
    yield
      foreign.foreach(response => assertEquals(response.status, Status.NotFound))
      absent.foreach(response => assertEquals(response.status, Status.NotFound))
      assertEquals(foreignBodies, absentBodies, "Ответы не должны выдавать существование задачи")
      assertEquals(listed.map(field[String](_, "name")), List("Личная задача"))
      assertEquals(days, Json.obj(id.toString -> List("2026-06-14").asJson))
      assertEquals(rows, 1)
  }

  test("IDOR: списки не показывают чужие задачи и отметки") {
    for
      (_, _, attacker) <- victimAndAttacker
      habits <- listHabits(attacker)
      days <- get("/api/checkins?from=2026-06-01&to=2026-06-30", attacker).flatMap(_.as[Json])
    yield
      assertEquals(habits, Nil)
      assertEquals(days, Json.obj())
  }

  test("IDOR: user_id в теле и параметрах игнорируется, берётся только из сессии") {
    def ownerId(habit: UUID): IO[UUID] =
      sql"SELECT user_id FROM habits WHERE id = $habit".query[UUID].unique.transact(xa)
    for
      (owner, victimHabit, attacker) <- victimAndAttacker
      victimId <- ownerId(victimHabit)
      created <- post(
        "/api/habits",
        attacker,
        habitBody().deepMerge(Json.obj("user_id" -> victimId.asJson))
      )
      createdId <- created.as[Json].map(field[UUID](_, "id"))
      createdOwner <- ownerId(createdId)
      viaParam <- get(s"/api/habits?today=$Today&user_id=$victimId", attacker)
        .flatMap(_.as[List[Json]])
      ownerHabits <- listHabits(owner)
    yield
      assertNotEquals(createdOwner, victimId)
      assertEquals(viaParam.map(field[UUID](_, "id")), List(createdId))
      assertEquals(ownerHabits.map(field[UUID](_, "id")), List(victimHabit))
  }

  test("IDOR: чужие id в order дают 400 и не меняют ни одну сторону") {
    for
      (owner, victimHabit, attacker) <- victimAndAttacker
      second <- createHabit(owner, "Вторая")
      mine <- createHabit(attacker, "Моя")
      withForeign <- put(
        "/api/habits/order",
        attacker,
        Some(Json.obj("ids" -> List(victimHabit, mine).asJson))
      )
      onlyForeign <- put(
        "/api/habits/order",
        attacker,
        Some(Json.obj("ids" -> List(second, victimHabit).asJson))
      )
      ownerList <- listHabits(owner)
    yield
      assertEquals(withForeign.status, Status.BadRequest)
      assertEquals(onlyForeign.status, Status.BadRequest)
      assertEquals(ownerList.map(field[UUID](_, "id")), List(victimHabit, second))
  }

  test("база не даёт записать отметку с user_id, отличным от владельца задачи") {
    for
      (_, id, attacker) <- victimAndAttacker
      attackerHabit <- createHabit(attacker)
      attackerId <- sql"SELECT user_id FROM habits WHERE id = $attackerHabit"
        .query[UUID]
        .unique
        .transact(xa)
      result <- sql"""
        INSERT INTO checkins (habit_id, user_id, day) VALUES ($id, $attackerId, ${Today})
      """.update.run.transact(xa).attempt
    yield result match
      case Left(error: java.sql.SQLException) => assertEquals(error.getSQLState, "23503")
      case other                              => fail(s"Ожидалось нарушение FK, получено: $other")
  }

  // ---------- Авторизация и CSRF ----------

  private val anyId = UUID.randomUUID

  List(
    Method.GET -> s"/api/habits?today=$Today",
    Method.POST -> "/api/habits",
    Method.POST -> "/api/habits/starter",
    Method.PUT -> "/api/habits/order",
    Method.PATCH -> s"/api/habits/$anyId",
    Method.DELETE -> s"/api/habits/$anyId",
    Method.PUT -> s"/api/habits/$anyId/checkins/2026-06-15",
    Method.DELETE -> s"/api/habits/$anyId/checkins/2026-06-15",
    Method.GET -> "/api/checkins?from=2026-06-01&to=2026-06-30"
  ).foreach { (method, path) =>
    test(s"без сессии 401: ${method.name} $path") {
      send(request(method, path, None)).map(r => assertEquals(r.status, Status.Unauthorized))
    }
  }

  test("CSRF: PATCH, PUT и DELETE без правильного Origin или Content-Type получают 403") {
    for
      token <- signUp
      id <- createHabit(token)
      foreign = Some("https://evil.test")
      responses <- List(
        request(Method.DELETE, s"/api/habits/$id", Some(token), origin = foreign),
        request(Method.DELETE, s"/api/habits/$id", Some(token), origin = None),
        request(Method.DELETE, s"/api/habits/$id", Some(token), json = false),
        request(Method.PUT, s"/api/habits/$id/checkins/2026-06-15", Some(token), json = false),
        request(
          Method.PATCH,
          s"/api/habits/$id",
          Some(token),
          Some(Json.obj("name" -> "x".asJson)),
          origin = foreign
        )
      ).traverse(send)
      listed <- listHabits(token)
      rows <- checkinRows(id)
    yield
      responses.foreach(response => assertEquals(response.status, Status.Forbidden))
      assertEquals(listed.map(field[String](_, "name")), List("Зал"))
      assertEquals(rows, 0)
  }

  test("запрос без тела проходит CSRF-проверку, если есть Content-Type (заметка для этапа 4)") {
    for
      token <- signUp
      id <- createHabit(token)
      checked <- send(request(Method.PUT, s"/api/habits/$id/checkins/2026-06-15", Some(token)))
      removed <- send(request(Method.DELETE, s"/api/habits/$id", Some(token)))
    yield
      assertEquals(checked.status, Status.NoContent)
      assertEquals(removed.status, Status.NoContent)
  }

  // ---------- Валидация ----------

  List(
    "пустое название" -> habitBody(name = ""),
    "название из пробелов" -> habitBody(name = "    "),
    "название длиннее 80" -> habitBody(name = "я" * 81),
    "нулевой символ в названии" -> habitBody(name = "a\u0000b"),
    "неизвестный цвет" -> habitBody(color = "red"),
    "цвет не в том регистре" -> habitBody(color = "Green"),
    "дата не того формата" -> habitBody(start = "15.06.2026"),
    "несуществующая дата" -> habitBody(start = "2026-02-30"),
    "дата со знаком" -> habitBody(start = "+2026-01-01"),
    "раньше 2000 года" -> habitBody(start = "1999-12-31"),
    "старт дальше года и суток запаса" -> habitBody(start = Today.plusDays(367).toString)
  ).foreach { (name, body) =>
    test(s"POST /api/habits отклоняется: $name") {
      for
        token <- signUp
        response <- post("/api/habits", token, body)
        code <- errorCode(response)
        listed <- listHabits(token)
      yield
        assertEquals(response.status, Status.BadRequest)
        assertEquals(code, "validation_failed")
        assertEquals(listed, Nil)
    }
  }

  test("непарный суррогат в названии: 400, а не 500") {
    // Как текст JSON: при кодировании строки в UTF-8 суррогат заменился бы на «?» ещё в тесте.
    val body = """{"name": "a\ud800b", "color": "green", "start_date": "2026-06-01"}"""
    for
      token <- signUp
      response <- send(
        Request[IO](Method.POST, uri"/api/habits")
          .withEntity(body)(using EntityEncoder.stringEncoder[IO])
          .withContentType(`Content-Type`(MediaType.application.json))
          .putHeaders(Header.Raw(ci"Origin", Site))
          .addCookie(CookieName, token)
      )
      code <- errorCode(response)
    yield
      assertEquals(response.status, Status.BadRequest)
      assertEquals(code, "validation_failed")
  }

  test("граничные значения принимаются: 80 символов и даты на краю диапазона") {
    for
      token <- signUp
      longName <- post("/api/habits", token, habitBody(name = "я" * 80))
      emoji <- post("/api/habits", token, habitBody(name = "😀" * 80))
      earliest <- post("/api/habits", token, habitBody(start = "2000-01-01"))
      latest <- post("/api/habits", token, habitBody(start = Today.plusDays(366).toString))
    yield List(longName, emoji, earliest, latest).foreach { response =>
      assertEquals(response.status, Status.Created)
    }
  }

  test("тело неверной формы: 400 с единым форматом") {
    for
      token <- signUp
      id <- createHabit(token)
      missingField <- post("/api/habits", token, Json.obj("name" -> "Зал".asJson))
      wrongType <- post("/api/habits", token, habitBody().deepMerge(Json.obj("name" -> 1.asJson)))
      emptyPatch <- patch(s"/api/habits/$id", token, Json.obj())
      badIds <- put("/api/habits/order", token, Some(Json.obj("ids" -> List("x").asJson)))
      badColorPatch <- patch(s"/api/habits/$id", token, Json.obj("color" -> "red".asJson))
    yield List(missingField, wrongType, emptyPatch, badIds, badColorPatch).foreach { response =>
      assertEquals(response.status, Status.BadRequest)
    }
  }

  test("order: пропуск, лишний id и повтор дают 400") {
    for
      token <- signUp
      a <- createHabit(token, "A")
      b <- createHabit(token, "B")
      missing <- put("/api/habits/order", token, Some(Json.obj("ids" -> List(a).asJson)))
      extra <- put(
        "/api/habits/order",
        token,
        Some(Json.obj("ids" -> List(a, b, UUID.randomUUID).asJson))
      )
      duplicate <- put("/api/habits/order", token, Some(Json.obj("ids" -> List(a, b, b).asJson)))
      listed <- listHabits(token)
    yield
      List(missing, extra, duplicate).foreach(r => assertEquals(r.status, Status.BadRequest))
      assertEquals(listed.map(field[UUID](_, "id")), List(a, b))
  }

  test("дата отметки: границы и плохие значения") {
    for
      token <- signUp
      id <- createHabit(token)
      day = (value: String) => put(s"/api/habits/$id/checkins/$value", token)
      earliest <- day("2000-01-01")
      tomorrow <- day(Today.plusDays(1).toString)
      tooOld <- day("1999-12-31")
      tooFar <- day(Today.plusDays(2).toString)
      garbage <- day("yesterday")
      impossible <- day("2026-13-01")
      notUuid <- put("/api/habits/not-a-uuid/checkins/2026-06-15", token)
    yield
      assertEquals(earliest.status, Status.NoContent)
      assertEquals(tomorrow.status, Status.NoContent)
      List(tooOld, tooFar, garbage, impossible).foreach { response =>
        assertEquals(response.status, Status.BadRequest)
      }
      assertEquals(notUuid.status, Status.NotFound)
  }

  test("today: не дальше суток от даты сервера") {
    val today = (value: String, token: String) => get(s"/api/habits?today=$value", token)
    for
      token <- signUp
      yesterday <- today(Today.minusDays(1).toString, token)
      tomorrow <- today(Today.plusDays(1).toString, token)
      old <- today(Today.minusDays(2).toString, token)
      future <- today(Today.plusDays(2).toString, token)
      garbage <- today("garbage", token)
      missing <- get("/api/habits", token)
    yield
      assertEquals(yesterday.status, Status.Ok)
      assertEquals(tomorrow.status, Status.Ok)
      List(old, future, garbage, missing).foreach { response =>
        assertEquals(response.status, Status.BadRequest)
      }
  }

  test("диапазон отметок: до 400 дней включительно, порядок и формат проверяются") {
    val range = (from: LocalDate, to: LocalDate, token: String) =>
      get(s"/api/checkins?from=$from&to=$to", token)
    val start = LocalDate.of(2025, 1, 1)
    for
      token <- signUp
      exact <- range(start, start.plusDays(399), token)
      tooLong <- range(start, start.plusDays(400), token)
      reversed <- range(start.plusDays(1), start, token)
      oneDay <- range(start, start, token)
      missing <- get("/api/checkins?from=2025-01-01", token)
      garbage <- get("/api/checkins?from=x&to=y", token)
    yield
      assertEquals(exact.status, Status.Ok)
      assertEquals(oneDay.status, Status.Ok)
      List(tooLong, reversed, missing, garbage).foreach { response =>
        assertEquals(response.status, Status.BadRequest)
      }
  }

  // ---------- N+1: число запросов не зависит от числа задач ----------

  private def countStatements[A](action: IO[A]): IO[(A, List[String])] =
    db()._2.set(Nil) *> action.flatMap(result => db()._2.get.map(result -> _.reverse))

  /** Быстрое наполнение напрямую в базу: сам код здесь не проверяется. */
  private def seedHabits(token: String, count: Int): IO[Unit] =
    val hash = SessionToken.parse(token).getOrElse(fail("Токен")).hash
    sql"""
      WITH owner AS (SELECT user_id FROM sessions WHERE token_hash = $hash),
      created AS (
        INSERT INTO habits (user_id, name, color, position, start_date)
        SELECT owner.user_id, 'Задача ' || n, 'teal', 100 + n, DATE '2026-01-01'
        FROM owner, generate_series(1, $count) AS n
        RETURNING id, user_id
      )
      INSERT INTO checkins (habit_id, user_id, day)
      SELECT id, user_id, DATE '2026-06-15' - d
      FROM created, generate_series(0, 29) AS d
    """.update.run.transact(xa).void

  /** Выполняет запрос при 1 и при 50 задачах и возвращает число запросов к базе в обоих случаях. */
  private def statementsAt1And50(action: String => IO[Response[IO]]): IO[(Int, Int)] =
    for
      token <- signUp
      _ <- seedHabits(token, 1)
      few <- countStatements(action(token))
      _ <- seedHabits(token, 49)
      many <- countStatements(action(token))
    yield
      assertEquals(few._1.status.isSuccess, true, s"Статус: ${few._1.status}")
      assertEquals(many._1.status.isSuccess, true, s"Статус: ${many._1.status}")
      (few._2.length, many._2.length)

  test("N+1: GET /api/habits — один запрос к данным при 1 и при 50 задачах") {
    statementsAt1And50(token => get(s"/api/habits?today=$Today", token)).map { counts =>
      assertEquals(counts, (2, 2), "Проверка сессии + один запрос к задачам")
    }
  }

  test("N+1: GET /api/checkins — один запрос к данным при 1 и при 50 задачах") {
    statementsAt1And50(token => get("/api/checkins?from=2026-01-01&to=2026-12-31", token)).map {
      counts => assertEquals(counts, (2, 2), "Проверка сессии + один запрос к отметкам")
    }
  }

  test("N+1: PUT /api/habits/order — один UPDATE при 1 и при 50 задачах") {
    val reorder = (token: String) =>
      listHabits(token).flatMap { habits =>
        val ids = habits.map(field[UUID](_, "id")).reverse
        countStatements(put("/api/habits/order", token, Some(Json.obj("ids" -> ids.asJson))))
      }
    for
      token <- signUp
      _ <- seedHabits(token, 1)
      few <- reorder(token)
      _ <- seedHabits(token, 49)
      many <- reorder(token)
    yield
      assertEquals(few._1.status, Status.NoContent)
      assertEquals(many._1.status, Status.NoContent)
      assertEquals(few._2.length, 3, "Сессия + блокировка + UPDATE")
      assertEquals(many._2.length, 3, "Сессия + блокировка + UPDATE")
  }

  test("стартовый набор — одна вставка") {
    for
      token <- signUp
      result <- countStatements(
        post("/api/habits/starter", token, Json.obj("start_date" -> "2026-06-15".asJson))
      )
    yield
      assertEquals(result._1.status, Status.Created)
      assertEquals(result._2.length, 2, "Проверка сессии + INSERT")
  }

  // ---------- Правильность серий: сравнение с прежней JS-логикой ----------

  final private case class StreakCase(
      name: String,
      today: LocalDate,
      days: List[LocalDate],
      streak: Int,
      best: Int
  ) derives io.circe.Decoder

  /** Эталон посчитан настоящими `currentStreak` и `bestStreak` из `frontend/src/lib/habits.js`: 20
    * граничных случаев и 150 случайных.
    */
  private val streakCases: IO[List[StreakCase]] = IO.blocking {
    val source = scala.io.Source.fromResource("streak-cases.json", getClass.getClassLoader)
    try io.circe.jawn.decode[List[StreakCase]](source.mkString).fold(throw _, identity)
    finally source.close()
  }

  private def newUser: ConnectionIO[UUID] =
    sql"""
      INSERT INTO users (email, password_hash)
      VALUES (${s"streak-${UUID.randomUUID}@example.com"}::citext, 'x')
      RETURNING id
    """.query[UUID].unique

  private def newHabit(userId: UUID, days: List[LocalDate]): ConnectionIO[UUID] =
    for
      habit <- HabitRepository.insert(userId, "Серия", "green", LocalDate.of(2026, 1, 1))
      _ <- Update[(UUID, UUID, LocalDate)]("INSERT INTO checkins VALUES (?, ?, ?)")
        .updateMany(days.map((habit.id, userId, _)))
    yield habit.id

  test("серии совпадают с JS-логикой на 170 случаях") {
    streakCases.flatMap { cases =>
      assertEquals(cases.length, 170)
      cases.traverse_ { testCase =>
        val check =
          for
            userId <- newUser
            _ <- newHabit(userId, testCase.days)
            result <- HabitRepository.listWithStreaks(userId, testCase.today)
          yield result.map(habit => (habit.streak, habit.best))
        check.transact(xa).map { result =>
          assertEquals(result, List((testCase.streak, testCase.best)), testCase.name)
        }
      }
    }
  }

  test("серии считаются отдельно по задачам и не смешиваются с другими пользователями") {
    val day = (back: Int) => Today.minusDays(back.toLong)
    val check =
      for
        user <- newUser
        other <- newUser
        three <- newHabit(user, List(day(2), day(1), day(0)))
        one <- newHabit(user, List(day(0)))
        none <- newHabit(user, Nil)
        _ <- newHabit(other, (0 to 9).toList.map(day))
        result <- HabitRepository.listWithStreaks(user, Today)
      yield (three, one, none, result.map(h => (h.habit.id, h.streak, h.best)))
    check.transact(xa).map { (three, one, none, result) =>
      assertEquals(result.toSet, Set((three, 3, 3), (one, 1, 1), (none, 0, 0)))
    }
  }

  test("отметка до даты старта входит в серию") {
    val check =
      for
        user <- newUser
        habit <- HabitRepository.insert(user, "Будущая", "green", Today.plusDays(30))
        _ <- HabitRepository.addCheckin(user, habit.id, Today)
        result <- HabitRepository.listWithStreaks(user, Today)
      yield result.map(h => (h.streak, h.best))
    check.transact(xa).assertEquals(List((1, 1)))
  }

  test("GET /api/habits отдаёт серии, посчитанные для today клиента") {
    for
      token <- signUp
      id <- createHabit(token)
      _ <- put(s"/api/habits/$id/checkins/${Today.minusDays(1)}", token)
      _ <- put(s"/api/habits/$id/checkins/${Today.minusDays(2)}", token)
      asToday <- listHabits(token, Today)
      asTomorrow <- listHabits(token, Today.plusDays(1))
    yield
      assertEquals(field[Int](asToday.head, "streak"), 2)
      assertEquals(field[Int](asToday.head, "best"), 2)
      assertEquals(field[Int](asTomorrow.head, "streak"), 0, "Серия уже прервана")
      assertEquals(field[Int](asTomorrow.head, "best"), 2)
  }
