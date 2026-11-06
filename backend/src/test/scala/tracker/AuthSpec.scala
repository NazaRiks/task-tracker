package tracker

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import com.comcast.ip4s.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.syntax.*
import munit.AnyFixture
import munit.catseffect.IOFixture
import org.http4s.*
import org.http4s.circe.*
import org.http4s.headers.`Content-Type`
import org.http4s.implicits.*
import org.typelevel.ci.*
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.testing.TestingLogger
import tracker.auth.*
import tracker.config.SecurityConfig
import tracker.http.{Api, ServerMiddleware}

import java.util.UUID
import scala.concurrent.duration.*

class AuthSpec extends munit.CatsEffectSuite:
  private val xa: IOFixture[doobie.Transactor[IO]] =
    ResourceSuiteLocalFixture("postgres", TestDatabase.transactor)

  override def munitFixtures: Seq[AnyFixture[?]] = List(xa)

  private val Site = "https://tracker.test"
  private val CookieName = "__Host-session"
  private val GoodPassword = "correct horse battery"

  private val strict = SecurityConfig(Site, cookieSecure = true, trustForwardedFor = false)

  private val generous = RateLimits(
    loginPerIpEmail = RateRule(1000, 1.hour),
    loginPerIp = RateRule(1000, 1.hour),
    registerPerIp = RateRule(1000, 1.hour)
  )

  private def newEmail: String = s"user-${UUID.randomUUID}@example.com"

  private def appWith(
      limits: RateLimits = generous,
      security: SecurityConfig = strict,
      passwords: IO[Passwords] = Passwords.create
  )(using Logger[IO]): IO[HttpApp[IO]] =
    for
      hashing <- passwords
      limiter <- RateLimiter.create()
      auth = AuthService(xa(), hashing)
    yield Api.httpApp(auth, limiter, limits, security, IO.unit)

  private given defaultLogger: Logger[IO] = TestingLogger.impl[IO]()

  private def connection(ip: IpAddress): Request.Connection =
    Request.Connection(
      local = SocketAddress(ip"127.0.0.1", port"8080"),
      remote = SocketAddress(ip, port"50000"),
      secure = true
    )

  private def post(
      path: String,
      body: Json = Json.obj(),
      ip: IpAddress = ip"10.0.0.1",
      origin: Option[String] = Some(Site),
      cookie: Option[String] = None
  ): Request[IO] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString(path))
      .withEntity(body)
      .withAttribute(Request.Keys.ConnectionInfo, connection(ip))
    val withOrigin = origin.fold(base)(o => base.putHeaders(Header.Raw(ci"Origin", o)))
    cookie.fold(withOrigin)(c => withOrigin.addCookie(CookieName, c))

  private def get(path: String, cookie: Option[String] = None): Request[IO] =
    val base = Request[IO](Method.GET, Uri.unsafeFromString(path))
    cookie.fold(base)(c => base.addCookie(CookieName, c))

  private def credentials(email: String, password: String = GoodPassword): Json =
    Json.obj("email" -> email.asJson, "password" -> password.asJson)

  private def sessionCookie(response: Response[IO]): ResponseCookie =
    response.cookies
      .find(_.name == CookieName)
      .getOrElse(fail(s"Нет куки сессии: ${response.cookies}"))

  private def register(app: HttpApp[IO], email: String): IO[String] =
    app.run(post("/api/auth/register", credentials(email))).map { response =>
      assertEquals(response.status, Status.Created)
      sessionCookie(response).content
    }

  private def errorCode(response: Response[IO]): IO[String] =
    response.as[Json].map(_.hcursor.get[String]("error").getOrElse(fail("Нет поля error")))

  private def sha256(token: String): Array[Byte] =
    SessionToken.parse(token).getOrElse(fail("Токен плохого формата")).hash

  // ---------- Полный цикл ----------

  test("полный цикл: регистрация → me → логаут → логин") {
    val email = newEmail
    for
      app <- appWith()
      registered <- app.run(post("/api/auth/register", credentials(email)))
      first = sessionCookie(registered).content
      me <- app.run(get("/api/auth/me", Some(first)))
      meBody <- me.as[Json]
      logout <- app.run(post("/api/auth/logout", cookie = Some(first)))
      afterLogout <- app.run(get("/api/auth/me", Some(first)))
      login <- app.run(post("/api/auth/login", credentials(email)))
      second = sessionCookie(login).content
      meAgain <- app.run(get("/api/auth/me", Some(second)))
    yield
      assertEquals(registered.status, Status.Created)
      assertEquals(me.status, Status.Ok)
      assertEquals(meBody.hcursor.get[String]("email"), Right(email))
      assertEquals(logout.status, Status.NoContent)
      assertEquals(sessionCookie(logout).maxAge, Some(0L))
      assertEquals(afterLogout.status, Status.Unauthorized)
      assertEquals(login.status, Status.Ok)
      assertEquals(meAgain.status, Status.Ok)
      assertNotEquals(first, second)
  }

  test("кука сессии: HttpOnly, Secure, SameSite=Lax, Path=/, 30 дней") {
    for
      app <- appWith()
      response <- app.run(post("/api/auth/register", credentials(newEmail)))
    yield
      val cookie = sessionCookie(response)
      assert(cookie.httpOnly)
      assert(cookie.secure)
      assertEquals(cookie.sameSite, Some(SameSite.Lax))
      assertEquals(cookie.path, Some("/"))
      assertEquals(cookie.maxAge, Some(30L * 24 * 60 * 60))
      assertEquals(cookie.domain, None)
      assertEquals(cookie.content.length, 43)
  }

  test("флаг Secure отключается настройкой для localhost") {
    val local = SecurityConfig("http://localhost:8080", cookieSecure = false, false)
    for
      app <- appWith(security = local)
      response <- app.run(
        post("/api/auth/register", credentials(newEmail), origin = Some(local.siteOrigin))
      )
    yield
      val cookie = response.cookies.find(_.name == "session").getOrElse(fail("Нет куки"))
      assert(!cookie.secure)
      assert(cookie.httpOnly)
      assertEquals(response.headers.get(ci"Strict-Transport-Security"), None)
  }

  test("в базе только SHA-256 токена, а сам токен и пароль не хранятся") {
    val email = newEmail
    for
      app <- appWith()
      token <- register(app, email)
      stored <- sql"""
        SELECT s.token_hash, u.password_hash
        FROM sessions s JOIN users u ON u.id = s.user_id
        WHERE u.email = $email::citext
      """.query[(Array[Byte], String)].unique.transact(xa())
    yield
      assertEquals(stored._1.toList, sha256(token).toList)
      assert(stored._2.startsWith("$argon2id$v=19$m=19456,t=2,p=1$"), stored._2)
      assert(!stored._2.contains(GoodPassword))
  }

  test("логин всегда создаёт новую сессию и отзывает предъявленную старую") {
    val email = newEmail
    for
      app <- appWith()
      first <- register(app, email)
      second <- app.run(post("/api/auth/login", credentials(email), cookie = Some(first)))
      oldMe <- app.run(get("/api/auth/me", Some(first)))
      newMe <- app.run(get("/api/auth/me", Some(sessionCookie(second).content)))
    yield
      assertNotEquals(sessionCookie(second).content, first)
      assertEquals(oldMe.status, Status.Unauthorized)
      assertEquals(newMe.status, Status.Ok)
  }

  test("logout-all завершает все сессии пользователя и не трогает чужие") {
    val email = newEmail
    for
      app <- appWith()
      first <- register(app, email)
      login <- app.run(post("/api/auth/login", credentials(email)))
      second = sessionCookie(login).content
      other <- register(app, newEmail)
      done <- app.run(post("/api/auth/logout-all", cookie = Some(first)))
      firstMe <- app.run(get("/api/auth/me", Some(first)))
      secondMe <- app.run(get("/api/auth/me", Some(second)))
      otherMe <- app.run(get("/api/auth/me", Some(other)))
    yield
      assertEquals(done.status, Status.NoContent)
      assertEquals(sessionCookie(done).maxAge, Some(0L))
      assertEquals(firstMe.status, Status.Unauthorized)
      assertEquals(secondMe.status, Status.Unauthorized)
      assertEquals(otherMe.status, Status.Ok)
  }

  // ---------- Логин ----------

  test("неверный пароль и несуществующий email дают одинаковый ответ") {
    val email = newEmail
    for
      app <- appWith()
      _ <- register(app, email)
      wrong <- app.run(post("/api/auth/login", credentials(email, "wrong password 123")))
      missing <- app.run(post("/api/auth/login", credentials(newEmail)))
      malformed <- app.run(post("/api/auth/login", credentials("not-an-email")))
      wrongBody <- wrong.as[Json]
      missingBody <- missing.as[Json]
      malformedBody <- malformed.as[Json]
    yield
      assertEquals(wrong.status, Status.Unauthorized)
      assertEquals(missing.status, Status.Unauthorized)
      assertEquals(malformed.status, Status.Unauthorized)
      assertEquals(wrongBody, missingBody)
      assertEquals(wrongBody, malformedBody)
      assertEquals(wrongBody.hcursor.get[String]("message"), Right("Неверный email или пароль"))
  }

  /** Считает вызовы, чтобы проверить саму причину защиты по времени, а не мерить его в тесте. */
  private def spyPasswords(calls: Ref[IO, List[String]]): IO[Passwords] =
    Passwords.create.map { real =>
      new Passwords:
        def hash(password: String): IO[String] = real.hash(password)
        def verify(password: String, hash: String): IO[Boolean] =
          calls.update("verify" :: _) *> real.verify(password, hash)
        def verifyAgainstDummy(password: String): IO[Unit] =
          calls.update("dummy" :: _) *> real.verifyAgainstDummy(password)
    }

  test("несуществующий email всё равно проходит проверку пароля по фиктивному хешу") {
    for
      calls <- Ref[IO].of(List.empty[String])
      app <- appWith(passwords = spyPasswords(calls))
      _ <- app.run(post("/api/auth/login", credentials(newEmail)))
      missing <- calls.get
      _ <- app.run(post("/api/auth/login", credentials("not-an-email")))
      malformed <- calls.get
    yield
      assertEquals(missing, List("dummy"))
      assertEquals(malformed, List("dummy", "dummy"))
  }

  test("email нормализуется: регистр и пробелы не создают второй аккаунт") {
    val email = newEmail
    for
      app <- appWith()
      first <- app.run(post("/api/auth/register", credentials(s"  ${email.toUpperCase}  ")))
      body <- first.as[Json]
      second <- app.run(post("/api/auth/register", credentials(email)))
      secondCode <- errorCode(second)
      login <- app.run(post("/api/auth/login", credentials(email.toUpperCase)))
    yield
      assertEquals(body.hcursor.get[String]("email"), Right(email))
      assertEquals(second.status, Status.Conflict)
      assertEquals(secondCode, "email_taken")
      assertEquals(login.status, Status.Ok)
  }

  // ---------- Валидация ----------

  private val invalidRegistrations = List(
    "пароль короче 10 символов" -> credentials(newEmail, "x" * 9),
    "пароль длиннее 128 символов" -> credentials(newEmail, "x" * 129),
    "email без @" -> credentials("no-at-sign.example.com"),
    "email без домена" -> credentials("user@localhost"),
    "email с пробелом внутри" -> credentials("us er@example.com"),
    "email длиннее 254 символов" -> credentials(("x" * 250) + "@example.com")
  )

  invalidRegistrations.foreach { (name, body) =>
    test(s"регистрация отклоняется: $name") {
      for
        app <- appWith()
        response <- app.run(post("/api/auth/register", body))
        code <- errorCode(response)
      yield
        assertEquals(response.status, Status.BadRequest)
        assertEquals(code, "validation_failed")
        assert(response.cookies.isEmpty)
    }
  }

  test("пароль на границах допустимой длины принимается, эмодзи считается за один символ") {
    for
      app <- appWith()
      min <- app.run(post("/api/auth/register", credentials(newEmail, "x" * 10)))
      max <- app.run(post("/api/auth/register", credentials(newEmail, "x" * 128)))
      emoji <- app.run(post("/api/auth/register", credentials(newEmail, "😀" * 128)))
    yield
      assertEquals(min.status, Status.Created)
      assertEquals(max.status, Status.Created)
      assertEquals(emoji.status, Status.Created)
  }

  // ---------- Сессии ----------

  test("просроченная сессия не работает, очистка удаляет только просроченные") {
    val email = newEmail
    for
      app <- appWith()
      expired <- register(app, email)
      alive <- register(app, newEmail)
      _ <- sql"""
        UPDATE sessions SET expires_at = now() - interval '1 second'
        WHERE token_hash = ${sha256(expired)}
      """.update.run.transact(xa())
      expiredMe <- app.run(get("/api/auth/me", Some(expired)))
      deleted <- AuthService(xa(), null).deleteExpiredSessions
      left <- sql"SELECT count(*) FROM sessions WHERE token_hash = ${sha256(expired)}"
        .query[Int]
        .unique
        .transact(xa())
      aliveMe <- app.run(get("/api/auth/me", Some(alive)))
    yield
      assertEquals(expiredMe.status, Status.Unauthorized)
      assert(deleted >= 1)
      assertEquals(left, 0)
      assertEquals(aliveMe.status, Status.Ok)
  }

  test("свежая сессия: запрос не пишет в базу и не обновляет куку") {
    for
      app <- appWith()
      token <- register(app, newEmail)
      before <- lastSeen(token)
      me <- app.run(get("/api/auth/me", Some(token)))
      after <- lastSeen(token)
    yield
      assertEquals(me.status, Status.Ok)
      assert(me.cookies.isEmpty)
      assertEquals(after, before)
  }

  test("скользящее продление: старше часа обновляются last_seen_at, expires_at и кука") {
    for
      app <- appWith()
      token <- register(app, newEmail)
      _ <- sql"""
        UPDATE sessions
        SET last_seen_at = now() - interval '2 hours', expires_at = now() + interval '1 day'
        WHERE token_hash = ${sha256(token)}
      """.update.run.transact(xa())
      me <- app.run(get("/api/auth/me", Some(token)))
      daysLeft <- sql"""
        SELECT extract(day FROM expires_at - now())::int,
               last_seen_at > now() - interval '1 minute'
        FROM sessions WHERE token_hash = ${sha256(token)}
      """.query[(Int, Boolean)].unique.transact(xa())
    yield
      assertEquals(me.status, Status.Ok)
      assertEquals(sessionCookie(me).content, token)
      assertEquals(sessionCookie(me).maxAge, Some(30L * 24 * 60 * 60))
      assert(daysLeft._1 >= 29, s"Осталось дней: ${daysLeft._1}")
      assert(daysLeft._2)
  }

  test("logout-all на сессии, которую пора продлить, очищает куку, а не продлевает её") {
    for
      app <- appWith()
      token <- register(app, newEmail)
      _ <- sql"""
        UPDATE sessions SET last_seen_at = now() - interval '2 hours'
        WHERE token_hash = ${sha256(token)}
      """.update.run.transact(xa())
      response <- app.run(post("/api/auth/logout-all", cookie = Some(token)))
    yield assertEquals(sessionCookie(response).maxAge, Some(0L))
  }

  private def lastSeen(token: String): IO[java.time.OffsetDateTime] =
    sql"SELECT last_seen_at FROM sessions WHERE token_hash = ${sha256(token)}"
      .query[java.time.OffsetDateTime]
      .unique
      .transact(xa())

  test("мусор в куке и отсутствие куки дают 401") {
    for
      app <- appWith()
      garbage <- app.run(get("/api/auth/me", Some("' OR 1=1 --")))
      unknown <- app.run(get("/api/auth/me", Some("A" * 43)))
      missing <- app.run(get("/api/auth/me"))
      code <- errorCode(missing)
    yield
      assertEquals(garbage.status, Status.Unauthorized)
      assertEquals(unknown.status, Status.Unauthorized)
      assertEquals(missing.status, Status.Unauthorized)
      assertEquals(code, "unauthorized")
  }

  // ---------- CSRF ----------

  private val badOrigins = List(
    "без Origin" -> None,
    "чужой Origin" -> Some("https://evil.test"),
    "Origin с другой схемой" -> Some("http://tracker.test"),
    "поддомен" -> Some("https://evil.tracker.test"),
    "null" -> Some("null")
  )

  badOrigins.foreach { (name, origin) =>
    test(s"CSRF: изменяющий запрос отклоняется, $name") {
      for
        app <- appWith()
        token <- register(app, newEmail)
        response <- app.run(post("/api/auth/logout-all", origin = origin, cookie = Some(token)))
        code <- errorCode(response)
        me <- app.run(get("/api/auth/me", Some(token)))
      yield
        assertEquals(response.status, Status.Forbidden)
        assertEquals(code, "csrf_rejected")
        assertEquals(me.status, Status.Ok, "Сессия не должна быть затронута")
    }
  }

  test("CSRF: Content-Type, который может отправить обычная форма, отклоняется") {
    for
      app <- appWith()
      token <- register(app, newEmail)
      form <- app.run(
        post("/api/auth/logout-all", cookie = Some(token))
          .withContentType(`Content-Type`(MediaType.application.`x-www-form-urlencoded`))
      )
      text <- app.run(
        post("/api/auth/logout-all", cookie = Some(token))
          .withContentType(`Content-Type`(MediaType.text.plain))
      )
      none <- app.run(
        post("/api/auth/logout-all", cookie = Some(token)).removeHeader[`Content-Type`]
      )
    yield
      assertEquals(form.status, Status.Forbidden)
      assertEquals(text.status, Status.Forbidden)
      assertEquals(none.status, Status.Forbidden)
  }

  test("CSRF распространяется и на login: чужой сайт не может войти под своим аккаунтом") {
    val email = newEmail
    for
      app <- appWith()
      _ <- register(app, email)
      response <- app.run(
        post("/api/auth/login", credentials(email), origin = Some("https://evil.test"))
      )
    yield
      assertEquals(response.status, Status.Forbidden)
      assert(response.cookies.isEmpty)
  }

  // ---------- Лимиты ----------

  private def assertRateLimited(response: Response[IO]): IO[Unit] =
    errorCode(response).map { code =>
      assertEquals(response.status, Status.TooManyRequests)
      assertEquals(code, "rate_limited")
      val retryAfter = response.headers
        .get(ci"Retry-After")
        .map(_.head.value.toLong)
        .getOrElse(fail("Нет Retry-After"))
      assert(retryAfter > 0, s"Retry-After: $retryAfter")
    }

  test("login: 429 после лимита по IP+email, другой email с того же IP не затронут") {
    val limits = generous.copy(loginPerIpEmail = RateRule(3, 1.hour))
    val email = newEmail
    for
      app <- appWith(limits)
      _ <- register(app, email)
      attempts <- List
        .fill(3)(post("/api/auth/login", credentials(email, "wrong password 123")))
        .traverse(app.run)
      blocked <- app.run(post("/api/auth/login", credentials(email)))
      _ <- assertRateLimited(blocked)
      otherEmail <- app.run(post("/api/auth/login", credentials(newEmail)))
      otherIp <- app.run(post("/api/auth/login", credentials(email), ip = ip"10.0.0.2"))
    yield
      assertEquals(attempts.map(_.status), List.fill(3)(Status.Unauthorized))
      assertEquals(otherEmail.status, Status.Unauthorized)
      assertEquals(otherIp.status, Status.Ok)
  }

  test("login: 429 после лимита по IP при переборе разных email") {
    val limits = generous.copy(loginPerIp = RateRule(3, 1.hour))
    for
      app <- appWith(limits)
      _ <- List.fill(3)(post("/api/auth/login", credentials(newEmail))).traverse(app.run)
      blocked <- app.run(post("/api/auth/login", credentials(newEmail)))
      _ <- assertRateLimited(blocked)
      otherIp <- app.run(post("/api/auth/login", credentials(newEmail), ip = ip"10.0.0.2"))
    yield assertEquals(otherIp.status, Status.Unauthorized)
  }

  test("login: заблокированная попытка не тратит хеширование") {
    val limits = generous.copy(loginPerIp = RateRule(1, 1.hour))
    for
      calls <- Ref[IO].of(List.empty[String])
      app <- appWith(limits, passwords = spyPasswords(calls))
      _ <- app.run(post("/api/auth/login", credentials(newEmail)))
      _ <- app.run(post("/api/auth/login", credentials(newEmail)))
      recorded <- calls.get
    yield assertEquals(recorded.length, 1)
  }

  test("register: 429 после лимита по IP") {
    val limits = generous.copy(registerPerIp = RateRule(2, 1.hour))
    for
      app <- appWith(limits)
      _ <- List.fill(2)(post("/api/auth/register", credentials(newEmail))).traverse(app.run)
      blocked <- app.run(post("/api/auth/register", credentials(newEmail)))
      _ <- assertRateLimited(blocked)
    yield ()
  }

  private def withForwardedFor(request: Request[IO], value: String): Request[IO] =
    request.putHeaders(Header.Raw(ci"X-Forwarded-For", value))

  test("X-Forwarded-For по умолчанию игнорируется: подделка не обходит лимит") {
    val limits = generous.copy(loginPerIp = RateRule(1, 1.hour))
    for
      app <- appWith(limits)
      _ <- app.run(withForwardedFor(post("/api/auth/login", credentials(newEmail)), "1.1.1.1"))
      blocked <- app.run(
        withForwardedFor(post("/api/auth/login", credentials(newEmail)), "2.2.2.2")
      )
    yield assertEquals(blocked.status, Status.TooManyRequests)
  }

  test("X-Forwarded-For при включённой настройке: берётся значение от нашего прокси, справа") {
    val limits = generous.copy(loginPerIp = RateRule(1, 1.hour))
    val trusting = strict.copy(trustForwardedFor = true)
    for
      app <- appWith(limits, trusting)
      first <- app.run(
        withForwardedFor(post("/api/auth/login", credentials(newEmail)), "9.9.9.9, 1.1.1.1")
      )
      spoofed <- app.run(
        withForwardedFor(post("/api/auth/login", credentials(newEmail)), "8.8.8.8, 1.1.1.1")
      )
      other <- app.run(
        withForwardedFor(post("/api/auth/login", credentials(newEmail)), "9.9.9.9, 2.2.2.2")
      )
    yield
      assertEquals(first.status, Status.Unauthorized)
      assertEquals(spoofed.status, Status.TooManyRequests)
      assertEquals(other.status, Status.Unauthorized)
  }

  test("адреса IPv6 из одной подсети /64 делят общий лимит") {
    val limits = generous.copy(loginPerIp = RateRule(1, 1.hour))
    for
      app <- appWith(limits)
      _ <- app.run(post("/api/auth/login", credentials(newEmail), ip = ip"2001:db8:1:1::1"))
      same <- app.run(post("/api/auth/login", credentials(newEmail), ip = ip"2001:db8:1:1::2"))
      other <- app.run(post("/api/auth/login", credentials(newEmail), ip = ip"2001:db8:1:2::1"))
    yield
      assertEquals(same.status, Status.TooManyRequests)
      assertEquals(other.status, Status.Unauthorized)
  }

  // ---------- Заголовки и ошибки ----------

  private def assertSecurityHeaders(response: Response[IO]): Unit =
    def header(name: CIString) = response.headers.get(name).map(_.head.value)
    assertEquals(
      header(ci"Content-Security-Policy"),
      Some(
        "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; " +
          "font-src 'self'; connect-src 'self'; frame-ancestors 'none'; base-uri 'self'; " +
          "form-action 'self'"
      )
    )
    assertEquals(header(ci"X-Content-Type-Options"), Some("nosniff"))
    assertEquals(header(ci"Referrer-Policy"), Some("strict-origin-when-cross-origin"))
    assert(header(ci"Permissions-Policy").isDefined)
    assert(header(ci"Strict-Transport-Security").isDefined)
    assertEquals(header(ci"Cache-Control"), Some("no-store"))

  test("заголовки безопасности есть в успешных ответах и в ошибках") {
    for
      app <- appWith()
      health <- app.run(get("/api/health"))
      notFound <- app.run(get("/api/nope"))
      code <- errorCode(notFound)
      unauthorized <- app.run(get("/api/auth/me"))
      forbidden <- app.run(post("/api/auth/login", origin = None))
    yield
      assertEquals(notFound.status, Status.NotFound)
      assertEquals(code, "not_found")
      List(health, notFound, unauthorized, forbidden).foreach(assertSecurityHeaders)
  }

  test("некорректное тело: 400 в едином формате, без подробностей") {
    val secret = "пароль-в-битом-json"
    val broken = Request[IO](Method.POST, uri"/api/auth/login")
      .withEntity(s"""{"email": "$secret""")
      .withContentType(`Content-Type`(MediaType.application.json))
      .putHeaders(Header.Raw(ci"Origin", Site))
    for
      app <- appWith()
      response <- app.run(broken)
      body <- response.as[Json]
      wrongShape <- app.run(post("/api/auth/login", Json.obj("email" -> 1.asJson)))
    yield
      assertEquals(response.status, Status.BadRequest)
      assertEquals(body.hcursor.get[String]("error"), Right("invalid_request"))
      assert(!body.noSpaces.contains(secret))
      assertEquals(wrongShape.status, Status.BadRequest)
  }

  test("тело больше 16 KB отклоняется") {
    val huge = credentials(newEmail, "x" * (17 * 1024))
    for
      app <- appWith()
      response <- app.run(post("/api/auth/register", huge))
      code <- errorCode(response)
    yield
      assertEquals(response.status, Status.PayloadTooLarge)
      assertEquals(code, "body_too_large")
  }

  test("500: JSON без стектрейса, request id в ответе и в логе") {
    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    val failing: HttpApp[IO] = HttpApp(_ => IO.raiseError(new RuntimeException("секрет из SQL")))
    val app = ServerMiddleware(strict)(failing)
    for
      response <- app.run(get("/api/health"))
      body <- response.as[Json]
      logged <- logger.logged
    yield
      assertEquals(response.status, Status.InternalServerError)
      assertEquals(body.hcursor.get[String]("error"), Right("internal_error"))
      assert(!body.noSpaces.contains("секрет из SQL"))
      assert(!body.noSpaces.contains("RuntimeException"))
      val requestId = response.headers.get(ci"X-Request-Id").map(_.head.value).getOrElse("")
      assert(requestId.nonEmpty)
      assert(logged.exists(_.message.contains(requestId)), s"В логе: $logged")
      assertSecurityHeaders(response)
  }

  test("пароли, токены и куки не попадают в логи") {
    val logger = TestingLogger.impl[IO]()
    given Logger[IO] = logger
    val email = newEmail
    for
      app <- appWith()
      token <- register(app, email)
      _ <- app.run(post("/api/auth/login", credentials(email, "wrong password 123")))
      _ <- app.run(post("/api/auth/logout", cookie = Some(token)))
      logged <- logger.logged
    yield
      val text = logged.mkString("\n")
      assert(!text.contains(GoodPassword))
      assert(!text.contains("wrong password 123"))
      assert(!text.contains(token))
  }

  test("toString не раскрывает секреты") {
    for token <- SessionToken.generate
    yield
      assert(!token.toString.contains(token.value))
      assert(!tracker.auth.Credentials("a@b.c", GoodPassword).toString.contains(GoodPassword))
      assert(!StoredUser(UUID.randomUUID, "a@b.c", "hash-value").toString.contains("hash-value"))
  }
