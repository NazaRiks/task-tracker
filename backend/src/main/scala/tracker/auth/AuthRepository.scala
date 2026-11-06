package tracker.auth

import cats.syntax.all.*
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.util.UUID
import scala.concurrent.duration.*

final case class User(id: UUID, email: String) derives Read

final case class StoredUser(id: UUID, email: String, passwordHash: String) derives Read:
  override def toString: String = s"StoredUser($id)"

final case class SessionRow(id: UUID, email: String, needsRenewal: Boolean) derives Read

/** Все сроки считает база (`now()`), поэтому у приложения и SQL одни и те же часы. */
object AuthRepository:

  /** `ON CONFLICT` вместо проверки «есть ли такой email»: нет гонки между двумя регистрациями. */
  def insertUser(email: Email, passwordHash: String): ConnectionIO[Option[User]] =
    sql"""
      INSERT INTO users (email, password_hash)
      VALUES (${email.value}::citext, $passwordHash)
      ON CONFLICT (email) DO NOTHING
      RETURNING id, email::text
    """.query[User].option

  def findUser(email: Email): ConnectionIO[Option[StoredUser]] =
    sql"""
      SELECT id, email::text, password_hash FROM users WHERE email = ${email.value}::citext
    """.query[StoredUser].option

  def insertSession(
      tokenHash: Array[Byte],
      userId: UUID,
      ttl: FiniteDuration
  ): ConnectionIO[Unit] =
    sql"""
      INSERT INTO sessions (token_hash, user_id, expires_at)
      VALUES ($tokenHash, $userId, now() + make_interval(secs => ${ttl.toSeconds.toDouble}))
    """.update.run.void

  def findSession(
      tokenHash: Array[Byte],
      renewAfter: FiniteDuration
  ): ConnectionIO[Option[SessionRow]] =
    sql"""
      SELECT u.id, u.email::text,
             s.last_seen_at < now() - make_interval(secs => ${renewAfter.toSeconds.toDouble})
      FROM sessions s
      JOIN users u ON u.id = s.user_id
      WHERE s.token_hash = $tokenHash AND s.expires_at > now()
    """.query[SessionRow].option

  /** Новый срок не выходит за абсолютный предел. Возвращает, сколько сессии осталось жить: кука не
    * должна переживать саму сессию. `None`, если сессию только что удалил другой запрос.
    */
  def renewSession(
      tokenHash: Array[Byte],
      ttl: FiniteDuration,
      maxLifetime: FiniteDuration
  ): ConnectionIO[Option[FiniteDuration]] =
    sql"""
      UPDATE sessions
      SET last_seen_at = now(),
          expires_at = LEAST(
            now() + make_interval(secs => ${ttl.toSeconds.toDouble}),
            created_at + make_interval(secs => ${maxLifetime.toSeconds.toDouble})
          )
      WHERE token_hash = $tokenHash
      RETURNING ceil(extract(epoch FROM expires_at - now()))::bigint
    """.query[Long].option.map(_.map(_.seconds))

  def deleteSession(tokenHash: Array[Byte]): ConnectionIO[Unit] =
    sql"DELETE FROM sessions WHERE token_hash = $tokenHash".update.run.void

  def deleteUserSessions(userId: UUID): ConnectionIO[Unit] =
    sql"DELETE FROM sessions WHERE user_id = $userId".update.run.void

  val deleteExpiredSessions: ConnectionIO[Int] =
    sql"DELETE FROM sessions WHERE expires_at <= now()".update.run
