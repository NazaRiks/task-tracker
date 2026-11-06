package tracker.auth

import java.util.Locale

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
    else if !Format.matches(email) then Left("Некорректный email")
    else Right(new Email(email))

object PasswordPolicy:
  val MinLength = 10
  val MaxLength = 128

  /** Длина в символах Unicode, а не в UTF-16: эмодзи считается за один символ. */
  private def length(password: String): Int =
    password.codePointCount(0, password.length)

  /** Верхняя граница защищает и от траты ресурсов на хеширование огромных строк. */
  def isTooLong(password: String): Boolean = length(password) > MaxLength

  def validate(password: String): Either[String, String] =
    if length(password) < MinLength || isTooLong(password) then
      Left(s"Пароль должен содержать от $MinLength до $MaxLength символов")
    else Right(password)
