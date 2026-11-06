package tracker

import cats.effect.IO
import doobie.implicits.*
import io.circe.Json
import munit.AnyFixture
import munit.catseffect.IOFixture
import org.http4s.circe.*
import org.http4s.implicits.*
import org.http4s.{Method, Request, Status}
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import tracker.db.Database
import tracker.http.HealthRoutes

import scala.concurrent.duration.*

class HealthSpec extends munit.CatsEffectSuite:
  private given Logger[IO] = NoOpLogger[IO]

  private val xa: IOFixture[doobie.Transactor[IO]] =
    ResourceSuiteLocalFixture("postgres", TestDatabase.transactor)

  override def munitFixtures: Seq[AnyFixture[?]] = List(xa)

  private val request = Request[IO](Method.GET, uri"/api/health")

  test("миграции применяются к настоящему Postgres") {
    sql"SELECT count(*) FROM pg_extension WHERE extname = 'citext'"
      .query[Int]
      .unique
      .transact(xa())
      .assertEquals(1)
  }

  test("GET /api/health отвечает 200, когда база доступна") {
    val routes = HealthRoutes.routes(Database.ping(xa())).orNotFound
    for
      response <- routes.run(request)
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.Ok)
      assertEquals(body, Json.obj("status" -> Json.fromString("ok")))
  }

  test("GET /api/health отвечает 503 и не раскрывает причину, когда база недоступна") {
    val routes =
      HealthRoutes.routes(IO.raiseError(new RuntimeException("секретная деталь"))).orNotFound
    for
      response <- routes.run(request)
      body <- response.as[Json]
    yield
      assertEquals(response.status, Status.ServiceUnavailable)
      assertEquals(body.hcursor.get[String]("error"), Right("db_unavailable"))
      assert(!body.noSpaces.contains("секретная деталь"))
  }

  test("GET /api/health отвечает 503 по таймауту, даже если проверку базы нельзя отменить") {
    // Так ведёт себя JDBC-вызов при зависшей базе. С обычным `timeout` тест упал бы по времени.
    val routes = HealthRoutes.routes(IO.sleep(5.minutes).uncancelable).orNotFound
    routes.run(request).map(response => assertEquals(response.status, Status.ServiceUnavailable))
  }
