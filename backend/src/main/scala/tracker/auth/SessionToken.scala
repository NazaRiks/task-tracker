package tracker.auth

import cats.effect.IO

import java.nio.charset.StandardCharsets.US_ASCII
import java.security.{MessageDigest, SecureRandom}
import java.util.Base64

/** Токен есть только в куке. `toString` скрыт, чтобы значение случайно не попало в лог. */
final class SessionToken private (val value: String):
  override def toString: String = "SessionToken(***)"

  /** SHA-256 здесь достаточно, медленный хеш не нужен: у токена 256 бит случайности, перебирать его
    * бессмысленно. Поиск идёт по хешу, поэтому время сравнения не раскрывает значение токена.
    */
  def hash: Array[Byte] =
    MessageDigest.getInstance("SHA-256").digest(value.getBytes(US_ASCII))

object SessionToken:
  private val ByteLength = 32
  private val Format = "^[A-Za-z0-9_-]{43}$".r // 32 байта в base64url без дополнения
  private val random = new SecureRandom()
  private val encoder = Base64.getUrlEncoder.withoutPadding

  val generate: IO[SessionToken] =
    IO.blocking {
      val bytes = new Array[Byte](ByteLength)
      random.nextBytes(bytes)
      new SessionToken(encoder.encodeToString(bytes))
    }

  /** Значение не того формата отсекается без обращения к базе. */
  def parse(value: String): Option[SessionToken] =
    Option.when(Format.matches(value))(new SessionToken(value))
