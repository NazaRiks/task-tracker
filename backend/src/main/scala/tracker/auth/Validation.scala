package tracker.auth

import cats.effect.IO

import java.nio.charset.StandardCharsets
import java.util.Locale
import scala.io.Source

object Text:

  /** Управляющие символы и непарные суррогаты: JSON их допускает (`\u0000`, `\ud800`), а Postgres
    * не может сохранить. Без этой проверки такой запрос заканчивался бы ошибкой 500.
    */
  def hasUnstorableChars(value: String): Boolean =
    value.codePoints.anyMatch { codePoint =>
      val kind = Character.getType(codePoint)
      kind == Character.CONTROL || kind == Character.SURROGATE
    }

/** Нормализованный и проверенный email. Создать его можно только через `Email.parse`. */
final case class Email private (value: String)

object Email:
  val MaxLength = 254

  // Намеренно простая проверка: настоящую проверку адреса даёт только письмо. Длина проверяется
  // раньше регулярного выражения, а само выражение линейно: возврат с перебором ему не нужен.
  private val Format = "^[^\\s@]+@[^\\s@.]+(\\.[^\\s@.]+)+$".r

  def normalize(raw: String): String = raw.strip.toLowerCase(Locale.ROOT)

  def parse(raw: String): Either[String, Email] =
    val email = normalize(raw)
    if email.length > MaxLength then Left(s"Email не должен быть длиннее $MaxLength символов")
    else if Text.hasUnstorableChars(email) || !Format.matches(email) then Left("Некорректный email")
    else Right(new Email(email))

/** Требования к новому паролю: длина и отсутствие в списке самых частых (NIST SP 800-63B).
  *
  * Проверка работает только при регистрации. Вход её не использует: отказ при входе не помог бы, а
  * лишь выдал бы, что пароль из списка.
  */
final class PasswordPolicy private[auth] (common: Set[String]):
  import PasswordPolicy.*

  /** Регистр и пробелы по краям не отличают пароль от известного: `Password123` тоже в списке. */
  def isCommon(password: String): Boolean =
    common.contains(password.strip.toLowerCase(Locale.ROOT))

  def validate(password: String): Either[String, String] =
    if length(password) < MinLength || isTooLong(password) then
      Left(s"Пароль должен содержать от $MinLength до $MaxLength символов")
    else if isCommon(password) then
      Left("Этот пароль слишком распространён и легко подбирается, выберите другой")
    else Right(password)

object PasswordPolicy:
  val MinLength = 10
  val MaxLength = 128

  private val BlocklistResource = "security/common-passwords.txt"

  /** Длина в символах Unicode, а не в UTF-16: эмодзи считается за один символ. */
  private def length(password: String): Int =
    password.codePointCount(0, password.length)

  /** Верхняя граница защищает и от траты ресурсов на хеширование огромных строк. */
  def isTooLong(password: String): Boolean = length(password) > MaxLength

  /** Локальный файл из образа, без внешних запросов. Если его нет, сервер не запустится: иначе
    * защита молча перестала бы работать.
    */
  val load: IO[PasswordPolicy] = IO.blocking {
    val stream = getClass.getClassLoader.getResourceAsStream(BlocklistResource)
    if stream == null then
      throw new IllegalStateException(s"Не найден список паролей: $BlocklistResource")
    val source = Source.fromInputStream(stream, StandardCharsets.UTF_8.name)
    try new PasswordPolicy(source.getLines().filter(_.nonEmpty).toSet)
    finally source.close()
  }
