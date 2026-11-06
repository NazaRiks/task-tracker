package tracker

import cats.effect.IO
import com.comcast.ip4s.*
import org.http4s.HttpRoutes
import org.http4s.dsl.io.*
import org.http4s.ember.server.EmberServerBuilder
import tracker.http.ClientIp

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import scala.concurrent.duration.*

/** Настоящий Ember и настоящее соединение. В остальных тестах адрес подставляется вручную, и они не
  * заметили бы, что сервер его не передаёт: все клиенты делили бы один лимит на ключ `unknown`.
  */
class ClientIpSpec extends munit.CatsEffectSuite:

  private def keyFromRealConnection(trustForwardedFor: Boolean): IO[String] =
    val routes = HttpRoutes.of[IO] { case request @ GET -> Root =>
      Ok(ClientIp.key(request, trustForwardedFor))
    }
    EmberServerBuilder
      .default[IO]
      .withHost(ipv4"127.0.0.1")
      .withPort(port"0")
      .withHttpApp(routes.orNotFound)
      .withShutdownTimeout(1.second) // HTTP-клиент держит keep-alive соединение открытым
      .build
      .use { server =>
        IO.blocking {
          val request = HttpRequest
            .newBuilder(URI.create(s"http://127.0.0.1:${server.address.getPort}/"))
            .header("X-Forwarded-For", "203.0.113.7")
            .build()
          HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString()).body()
        }
      }

  test("IP берётся из соединения, а поддельный X-Forwarded-For игнорируется") {
    keyFromRealConnection(trustForwardedFor = false).assertEquals("127.0.0.1")
  }

  test("при включённой настройке X-Forwarded-For учитывается") {
    keyFromRealConnection(trustForwardedFor = true).assertEquals("203.0.113.7")
  }
