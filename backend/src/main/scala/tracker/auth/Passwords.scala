package tracker.auth

import cats.effect.IO
import cats.effect.std.Semaphore
import com.password4j.types.Argon2
import com.password4j.{Argon2Function, Password}

trait Passwords:
  def hash(password: String): IO[String]
  def verify(password: String, hash: String): IO[Boolean]

  /** Та же работа, что и в `verify`, когда пользователя нет: время ответа не выдаёт, есть ли email.
    */
  def verifyAgainstDummy(password: String): IO[Unit]

object Passwords:
  // Единственное место с параметрами Argon2id: минимальная конфигурация OWASP (m=19 MiB, t=2, p=1).
  private val MemoryKiB = 19456
  private val Iterations = 2
  private val Parallelism = 1
  private val OutputBytes = 32
  private val SaltBytes = 16

  // Каждое вычисление занимает 19 MiB и ядро процессора. Без предела поток запросов к login и
  // register исчерпал бы память и вытеснил остальную работу. Предел здесь — около 76 MiB.
  private val MaxConcurrentHashes = 4

  private val function =
    Argon2Function.getInstance(MemoryKiB, Iterations, Parallelism, OutputBytes, Argon2.ID)

  private def computeHash(password: String): IO[String] =
    IO.blocking(Password.hash(password).addRandomSalt(SaltBytes).`with`(function).getResult)

  val create: IO[Passwords] =
    for
      permits <- Semaphore[IO](MaxConcurrentHashes.toLong)
      // Настоящий хеш случайной строки с теми же параметрами, что и у пользователей.
      dummyHash <- IO.randomUUID.flatMap(id => computeHash(id.toString))
    yield new Passwords:
      def hash(password: String): IO[String] =
        permits.permit.use(_ => computeHash(password))

      def verify(password: String, hash: String): IO[Boolean] =
        permits.permit.use { _ =>
          // Параметры берутся из самого хеша: старые хеши продолжат работать при их смене.
          IO.blocking(
            Password.check(password, hash).`with`(Argon2Function.getInstanceFromHash(hash))
          )
        }

      def verifyAgainstDummy(password: String): IO[Unit] =
        verify(password, dummyHash).void
