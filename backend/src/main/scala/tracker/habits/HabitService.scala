package tracker.habits

import cats.effect.IO
import doobie.Transactor
import doobie.implicits.*

import java.time.LocalDate
import java.util.UUID

object HabitService:
  val Starter: List[(String, String)] = List(
    "Программирование" -> "green",
    "Словенский" -> "blue",
    "Английский" -> "violet",
    "Зал" -> "amber"
  )

/** Одна транзакция на операцию. Входные данные уже проверены в `HabitRules`. */
final class HabitService(xa: Transactor[IO]):

  def list(userId: UUID, today: LocalDate): IO[List[HabitWithStreaks]] =
    HabitRepository.listWithStreaks(userId, today).transact(xa)

  /** Отметки по задачам. Группировка в памяти линейная и не обращается к базе. */
  def checkins(userId: UUID, from: LocalDate, to: LocalDate): IO[Map[UUID, List[LocalDate]]] =
    HabitRepository
      .checkinsInRange(userId, from, to)
      .transact(xa)
      .map(_.groupMap(_._1)(_._2))

  def create(userId: UUID, name: String, color: String, startDate: LocalDate): IO[Habit] =
    HabitRepository.insert(userId, name, color, startDate).transact(xa)

  /** @return `None`, если у пользователя уже есть задачи */
  def createStarter(userId: UUID, startDate: LocalDate): IO[Option[List[Habit]]] =
    HabitRepository
      .insertStarter(userId, HabitService.Starter, startDate)
      .transact(xa)
      .map(habits => Option.when(habits.nonEmpty)(habits))

  def update(userId: UUID, habitId: UUID, patch: HabitPatch): IO[Option[Habit]] =
    HabitRepository.update(userId, habitId, patch).transact(xa)

  def delete(userId: UUID, habitId: UUID): IO[Boolean] =
    HabitRepository.delete(userId, habitId).transact(xa).map(_ > 0)

  /** @return `false`, если набор `ids` не совпадает с задачами пользователя */
  def reorder(userId: UUID, ids: List[UUID]): IO[Boolean] =
    val requested = ids.toSet
    val transaction =
      for
        existing <- HabitRepository.lockIds(userId)
        matches = ids.length == requested.size && existing.toSet == requested
        _ <- if matches then HabitRepository.reorder(userId, ids) else doobie.free.connection.unit
      yield matches
    transaction.transact(xa)

  def addCheckin(userId: UUID, habitId: UUID, day: LocalDate): IO[Boolean] =
    HabitRepository.addCheckin(userId, habitId, day).transact(xa)

  def removeCheckin(userId: UUID, habitId: UUID, day: LocalDate): IO[Boolean] =
    HabitRepository.removeCheckin(userId, habitId, day).transact(xa)
