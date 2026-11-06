package tracker.auth

import cats.effect.IO
import cats.syntax.all.*
import doobie.Transactor
import doobie.implicits.*

import java.util.UUID
import scala.concurrent.duration.*

/** `toString` скрыт: запрос с паролем нельзя случайно записать в лог. */
final case class Credentials(email: String, password: String):
  override def toString: String = "Credentials(***)"

final case class NewSession(user: User, token: SessionToken)

/** @param renewedFor
  *   если при этом запросе сессия продлена — на сколько; столько же должна жить кука
  */
final case class Authenticated(
    user: User,
    token: SessionToken,
    renewedFor: Option[FiniteDuration]
)

enum RegisterError:
  case Invalid(message: String)
  case EmailTaken

object AuthService:
  val SessionTtl: FiniteDuration = 30.days

  /** Украденная сессия не должна жить вечно, даже если ею постоянно пользуются. То же значение
    * стоит в ограничении `sessions_absolute_lifetime` (миграция V4).
    */
  val SessionMaxLifetime: FiniteDuration = 90.days

  /** Реже база не обновляется: чтение сессии на каждый запрос не становится записью. */
  val RenewAfter: FiniteDuration = 1.hour

/** Хеширование всегда выполняется вне транзакции: Argon2 работает десятки миллисекунд, и всё это
  * время соединение из пула было бы занято.
  */
final class AuthService(xa: Transactor[IO], passwords: Passwords, passwordPolicy: PasswordPolicy):
  import AuthService.*

  def register(credentials: Credentials): IO[Either[RegisterError, NewSession]] =
    val validated = for
      email <- Email.parse(credentials.email)
      password <- passwordPolicy.validate(credentials.password)
    yield (email, password)

    validated match
      case Left(message)            => IO.pure(Left(RegisterError.Invalid(message)))
      case Right((email, password)) =>
        for
          hash <- passwords.hash(password)
          token <- SessionToken.generate
          // Пользователь и сессия создаются атомарно: не бывает аккаунта, в который не вошли.
          created <- AuthRepository
            .insertUser(email, hash)
            .flatMap(_.traverse { user =>
              AuthRepository.insertSession(token.hash, user.id, SessionTtl).as(user)
            })
            .transact(xa)
        yield created.map(NewSession(_, token)).toRight(RegisterError.EmailTaken)

  /** Все причины отказа неразличимы и для вызывающего кода, и по времени работы. */
  def login(credentials: Credentials): IO[Option[NewSession]] =
    if PasswordPolicy.isTooLong(credentials.password) then IO.pure(None)
    else
      for
        stored <- Email.parse(credentials.email).toOption.flatTraverse { email =>
          AuthRepository.findUser(email).transact(xa)
        }
        session <- stored match
          case None =>
            passwords.verifyAgainstDummy(credentials.password).as(None)
          case Some(user) =>
            passwords.verify(credentials.password, user.passwordHash).flatMap {
              case false => IO.pure(None)
              case true  => createSession(User(user.id, user.email)).map(Some(_))
            }
      yield session

  private def createSession(user: User): IO[NewSession] =
    for
      token <- SessionToken.generate
      _ <- AuthRepository.insertSession(token.hash, user.id, SessionTtl).transact(xa)
    yield NewSession(user, token)

  /** Скользящее продление: запись в базу не чаще раза в `RenewAfter` и не дальше абсолютного срока.
    */
  def authenticate(token: SessionToken): IO[Option[Authenticated]] =
    val query = AuthRepository.findSession(token.hash, RenewAfter).flatMap {
      case None      => None.pure[doobie.ConnectionIO]
      case Some(row) =>
        val user = User(row.id, row.email)
        if row.needsRenewal then
          AuthRepository
            .renewSession(token.hash, SessionTtl, SessionMaxLifetime)
            .map(_.map(remaining => Authenticated(user, token, Some(remaining))))
        else Some(Authenticated(user, token, renewedFor = None)).pure[doobie.ConnectionIO]
    }
    query.transact(xa)

  def logout(token: SessionToken): IO[Unit] =
    AuthRepository.deleteSession(token.hash).transact(xa)

  def logoutAll(userId: UUID): IO[Unit] =
    AuthRepository.deleteUserSessions(userId).transact(xa)

  val deleteExpiredSessions: IO[Int] =
    AuthRepository.deleteExpiredSessions.transact(xa)
