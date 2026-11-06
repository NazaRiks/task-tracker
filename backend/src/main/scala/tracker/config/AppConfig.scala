package tracker.config

import cats.effect.IO
import cats.syntax.all.*
import ciris.*
import com.comcast.ip4s.{Host, Port}

import java.net.URI
import scala.util.Try

final case class HttpConfig(host: Host, port: Port)

/** Пароль завёрнут в `Secret`: его `toString` не раскрывает значение, поэтому случайно записать
  * конфиг с паролем в лог нельзя.
  */
final case class DbCredentials(user: String, password: Secret[String])

/** Настройки пула: только роль приложения. Данных владельца схемы здесь нет намеренно. */
final case class DbConfig(url: String, app: DbCredentials, poolSize: Int)

/** Настройки режима миграций: только владелец схемы. Сервер эти переменные не читает. */
final case class MigrationConfig(url: String, owner: DbCredentials)

object MigrationConfig:
  val load: IO[MigrationConfig] = (
    env("DB_URL"),
    (env("DB_MIGRATION_USER"), env("DB_MIGRATION_PASSWORD").secret).parMapN(DbCredentials.apply)
  ).parMapN(MigrationConfig.apply).load[IO]

/** @param siteOrigin
  *   адрес сайта, с которым сверяется заголовок Origin
  * @param cookieSecure
  *   флаг Secure у куки и заголовок HSTS; выключается только для http://localhost
  * @param trustForwardedFor
  *   брать IP из X-Forwarded-For; включать только за своим обратным прокси, иначе клиент подделает
  *   заголовок и обойдёт лимиты
  */
final case class SecurityConfig(
    siteOrigin: String,
    cookieSecure: Boolean,
    trustForwardedFor: Boolean
)

final case class AppConfig(http: HttpConfig, db: DbConfig, security: SecurityConfig)

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
    (env("DB_USER"), env("DB_PASSWORD").secret).parMapN(DbCredentials.apply),
    env("DB_POOL_SIZE").as[Int].default(10)
  ).parMapN(DbConfig.apply)

  /** Origin браузера — это ровно `схема://хост[:порт]`, с адресом с путём сравнение никогда не
    * пройдёт. Поэтому ошибка в настройке должна быть видна при запуске.
    */
  private def isOrigin(value: String): Boolean =
    Try(new URI(value)).toOption.exists { uri =>
      Set("http", "https").contains(uri.getScheme) &&
      uri.getHost != null &&
      uri.getRawPath.isEmpty &&
      uri.getRawQuery == null &&
      uri.getRawFragment == null
    }

  private val security: ConfigValue[IO, SecurityConfig] = (
    env("SITE_ORIGIN").as[String].evalMap { value =>
      if isOrigin(value) then IO.pure(value)
      else IO.raiseError(new IllegalArgumentException(s"SITE_ORIGIN не похож на Origin: $value"))
    },
    // Безопасное значение по умолчанию: без явной настройки кука уходит только по HTTPS.
    env("COOKIE_SECURE").as[Boolean].default(true),
    env("TRUST_X_FORWARDED_FOR").as[Boolean].default(false)
  ).parMapN(SecurityConfig.apply)

  /** `parMapN` собирает сразу все ошибки: при запуске видно каждую незаданную переменную. */
  val load: IO[AppConfig] = (http, db, security).parMapN(AppConfig.apply).load[IO]
