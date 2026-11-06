package tracker.config

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import com.comcast.ip4s.{Host, Port}

final case class HttpConfig(host: Host, port: Port)

/** Пароль завёрнут в `Secret`: его `toString` не раскрывает значение, поэтому случайно записать
  * конфиг с паролем в лог нельзя.
  */
final case class DbConfig(url: String, user: String, password: Secret[String], poolSize: Int)

final case class AppConfig(http: HttpConfig, db: DbConfig)

object AppConfig:
  private given ConfigDecoder[String, Host] =
    ConfigDecoder[String].mapOption("host")(Host.fromString)

  private given ConfigDecoder[String, Port] =
    ConfigDecoder[String, Int].mapOption("port")(Port.fromInt)

  private val http: ConfigValue[IO, HttpConfig] = (
    env("HTTP_HOST").as[Host].default(Host.fromString("0.0.0.0").get),
    env("HTTP_PORT").as[Port].default(Port.fromInt(8080).get)
  ).parMapN(HttpConfig.apply)

  private val db: ConfigValue[IO, DbConfig] = (
    env("DB_URL"),
    env("DB_USER"),
    env("DB_PASSWORD").secret,
    env("DB_POOL_SIZE").as[Int].default(10)
  ).parMapN(DbConfig.apply)

  /** `parMapN` собирает сразу все ошибки: при запуске видно каждую незаданную переменную. */
  val load: IO[AppConfig] = (http, db).parMapN(AppConfig.apply).load[IO]
