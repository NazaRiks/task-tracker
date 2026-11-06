package tracker.habits

import cats.effect.IO
import tracker.auth.Text

import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.time.{LocalDate, ZoneOffset}
import scala.util.Try

/** Проверка входных данных. Клиент работает с локальными датами, а сервер знает только UTC, поэтому
  * везде, где дата сравнивается с «сегодня», есть запас в один день на разницу часовых поясов (от
  * UTC-12 до UTC+14).
  */
object HabitRules:
  val MaxNameLength = 80
  val MaxRangeDays = 400
  val MinDate: LocalDate = LocalDate.of(2000, 1, 1)
  val Colors: Set[String] =
    Set("green", "blue", "violet", "amber", "teal", "pink", "orange", "slate")

  /** Дату старта можно сдвинуть на 365 дней от локальной даты клиента, а она может быть на день
    * впереди UTC.
    */
  private val MaxStartAheadDays = 365 + 1

  val utcToday: IO[LocalDate] =
    IO.realTimeInstant.map(LocalDate.ofInstant(_, ZoneOffset.UTC))

  /** Ровно `YYYY-MM-DD`. Сам разбор строгий (2026-02-30 не пройдёт), а длина отсекает допустимые в
    * ISO `+12026-01-01` и `-0001-01-01`.
    */
  def parseDate(raw: String): Option[LocalDate] =
    Option.when(raw.length == 10)(raw).flatMap { value =>
      Try(LocalDate.parse(value, DateTimeFormatter.ISO_LOCAL_DATE)).toOption
    }

  def name(raw: String): Either[String, String] =
    val clean = raw.strip
    val length = clean.codePointCount(0, clean.length)
    if length < 1 || length > MaxNameLength then
      Left(s"Название должно содержать от 1 до $MaxNameLength символов")
    else if Text.hasUnstorableChars(clean) then Left("Название содержит недопустимые символы")
    else Right(clean)

  def color(raw: String): Either[String, String] =
    Either.cond(Colors.contains(raw), raw, "Неизвестный цвет")

  def startDate(raw: String, today: LocalDate): Either[String, LocalDate] =
    parseDate(raw)
      .filter(date => !date.isBefore(MinDate) && !date.isAfter(today.plusDays(MaxStartAheadDays)))
      .toRight("Некорректная дата старта")

  /** Отметить можно любой прошедший день и «сегодня» клиента, который может быть завтра по UTC. */
  def checkinDay(raw: String, today: LocalDate): Either[String, LocalDate] =
    parseDate(raw)
      .filter(date => !date.isBefore(MinDate) && !date.isAfter(today.plusDays(1)))
      .toRight("Некорректная дата отметки")

  /** «Сегодня» клиента не дальше одного дня от UTC-даты сервера. */
  def clientToday(raw: Option[String], today: LocalDate): Either[String, LocalDate] =
    raw
      .flatMap(parseDate)
      .filter(date => Math.abs(ChronoUnit.DAYS.between(today, date)) <= 1)
      .toRight("Параметр today должен быть текущей датой в формате YYYY-MM-DD")

  def range(from: Option[String], to: Option[String]): Either[String, (LocalDate, LocalDate)] =
    (from.flatMap(parseDate), to.flatMap(parseDate)) match
      case (Some(start), Some(end)) if !end.isBefore(start) =>
        // Обе границы включены.
        Either.cond(
          ChronoUnit.DAYS.between(start, end) < MaxRangeDays,
          (start, end),
          s"Диапазон не должен превышать $MaxRangeDays дней"
        )
      case _ => Left("Параметры from и to должны быть датами YYYY-MM-DD, from не позже to")
