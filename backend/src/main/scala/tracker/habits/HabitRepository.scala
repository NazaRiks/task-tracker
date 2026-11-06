package tracker.habits

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

import java.time.{LocalDate, OffsetDateTime}
import java.util.UUID

final case class Habit(
    id: UUID,
    name: String,
    color: String,
    position: Int,
    startDate: LocalDate,
    createdAt: OffsetDateTime
) derives Read

final case class HabitWithStreaks(habit: Habit, streak: Int, best: Int) derives Read

/** Частичное изменение: `None` оставляет поле как есть. */
final case class HabitPatch(
    name: Option[String],
    color: Option[String],
    startDate: Option[LocalDate]
)

/** Каждая функция получает `userId`, и он есть в условии каждого запроса: изоляцию пользователей
  * нельзя забыть, не изменив сигнатуру. Значение приходит только из сессии (`AuthGuard`).
  */
object HabitRepository:

  /** Задачи с серией и рекордом одним запросом, без цикла по задачам (gaps-and-islands).
    *
    * Если из даты вычесть порядковый номер отметки в задаче, у дней подряд результат одинаковый: он
    * и служит номером «острова». Рекорд — самый длинный остров, причём считаются и отметки на
    * завтра (сегодня у клиента может быть завтра по UTC).
    *
    * Текущая серия заканчивается сегодня либо, если сегодня ещё не отмечено, вчера. Отметки после
    * `today` в неё не входят. Эти две ветки не могут сработать для одной задачи одновременно:
    * остров с концом вчера означает, что сегодня не отмечено.
    */
  def listWithStreaks(userId: UUID, today: LocalDate): ConnectionIO[List[HabitWithStreaks]] =
    sql"""
      WITH runs AS (
        SELECT habit_id, min(day) AS first_day, max(day) AS last_day, count(*)::int AS length
        FROM (
          SELECT habit_id, day,
                 day - (row_number() OVER (PARTITION BY habit_id ORDER BY day))::int AS island
          FROM checkins
          WHERE user_id = $userId
        ) numbered
        GROUP BY habit_id, island
      ), totals AS (
        SELECT habit_id,
               max(length) AS best,
               max(CASE
                     WHEN $today::date BETWEEN first_day AND last_day
                       THEN $today::date - first_day + 1
                     WHEN last_day = $today::date - 1 THEN length
                     ELSE 0
                   END) AS streak
        FROM runs
        GROUP BY habit_id
      )
      SELECT h.id, h.name, h.color, h.position, h.start_date, h.created_at,
             COALESCE(t.streak, 0), COALESCE(t.best, 0)
      FROM habits h
      LEFT JOIN totals t ON t.habit_id = h.id
      WHERE h.user_id = $userId
      ORDER BY h.position, h.created_at, h.id
    """.query[HabitWithStreaks].to[List]

  /** Условие только по `checkins`: составной внешний ключ гарантирует, что `user_id` отметки равен
    * владельцу задачи, поэтому соединение с `habits` не нужно.
    */
  def checkinsInRange(
      userId: UUID,
      from: LocalDate,
      to: LocalDate
  ): ConnectionIO[List[(UUID, LocalDate)]] =
    sql"""
      SELECT habit_id, day
      FROM checkins
      WHERE user_id = $userId AND day BETWEEN $from AND $to
      ORDER BY habit_id, day
    """.query[(UUID, LocalDate)].to[List]

  /** Новая задача встаёт в конец. Позицию считает база: клиенту не нужно её знать. */
  def insert(userId: UUID, name: String, color: String, startDate: LocalDate): ConnectionIO[Habit] =
    sql"""
      INSERT INTO habits (user_id, name, color, position, start_date)
      SELECT $userId::uuid, $name, $color, COALESCE(max(position) + 1, 0), $startDate::date
      FROM habits
      WHERE user_id = $userId
      RETURNING id, name, color, position, start_date, created_at
    """.query[Habit].unique

  /** Весь набор одним запросом. Только для пользователя без задач: повторный вызов из второй
    * вкладки не удвоит список. Пустой результат означает, что задачи уже есть.
    */
  def insertStarter(
      userId: UUID,
      starter: List[(String, String)],
      startDate: LocalDate
  ): ConnectionIO[List[Habit]] =
    val names = starter.map(_._1).toArray
    val colors = starter.map(_._2).toArray
    sql"""
      INSERT INTO habits (user_id, name, color, position, start_date)
      SELECT $userId::uuid, s.name, s.color, (s.ord - 1)::int, $startDate::date
      FROM unnest($names::text[], $colors::text[]) WITH ORDINALITY AS s(name, color, ord)
      WHERE NOT EXISTS (SELECT 1 FROM habits WHERE user_id = $userId)
      RETURNING id, name, color, position, start_date, created_at
    """.query[Habit].to[List].map(_.sortBy(_.position))

  def update(userId: UUID, habitId: UUID, patch: HabitPatch): ConnectionIO[Option[Habit]] =
    sql"""
      UPDATE habits
      SET name = COALESCE(${patch.name}::text, name),
          color = COALESCE(${patch.color}::text, color),
          start_date = COALESCE(${patch.startDate}::date, start_date)
      WHERE id = $habitId AND user_id = $userId
      RETURNING id, name, color, position, start_date, created_at
    """.query[Habit].option

  def delete(userId: UUID, habitId: UUID): ConnectionIO[Int] =
    sql"DELETE FROM habits WHERE id = $habitId AND user_id = $userId".update.run

  /** Id задач пользователя с блокировкой строк: до конца транзакции набор не изменят и не удалят.
    */
  def lockIds(userId: UUID): ConnectionIO[List[UUID]] =
    sql"SELECT id FROM habits WHERE user_id = $userId ORDER BY id FOR UPDATE"
      .query[UUID]
      .to[List]

  /** Весь порядок одним запросом, без UPDATE на каждую задачу. */
  def reorder(userId: UUID, ids: List[UUID]): ConnectionIO[Int] =
    val array = ids.toArray
    sql"""
      UPDATE habits h
      SET position = (o.ord - 1)::int
      FROM unnest($array::uuid[]) WITH ORDINALITY AS o(id, ord)
      WHERE h.id = o.id AND h.user_id = $userId
    """.update.run

  /** Строки в `checkins` могло не добавиться по двум причинам: отметка уже есть (это успех, запрос
    * идемпотентный) или задача чужая (это 404). Поэтому результат считается не по вставке, а по
    * `owned`. Вставка в CTE выполняется в любом случае.
    *
    * `FOR KEY SHARE` не даёт параллельному удалению задачи вклиниться между проверкой и вставкой:
    * иначе вместо 404 получилась бы ошибка внешнего ключа.
    *
    * @return
    *   `false`, если такой задачи у пользователя нет
    */
  def addCheckin(userId: UUID, habitId: UUID, day: LocalDate): ConnectionIO[Boolean] =
    sql"""
      WITH owned AS (
        SELECT id, user_id FROM habits WHERE id = $habitId AND user_id = $userId FOR KEY SHARE
      ), inserted AS (
        INSERT INTO checkins (habit_id, user_id, day)
        SELECT id, user_id, $day::date FROM owned
        ON CONFLICT (habit_id, day) DO NOTHING
      )
      SELECT count(*) FROM owned
    """.query[Int].unique.map(_ > 0)

  /** @return
    *   `false`, если такой задачи у пользователя нет; отсутствие самой отметки — успех
    */
  def removeCheckin(userId: UUID, habitId: UUID, day: LocalDate): ConnectionIO[Boolean] =
    sql"""
      WITH owned AS (
        SELECT id FROM habits WHERE id = $habitId AND user_id = $userId
      ), deleted AS (
        DELETE FROM checkins
        WHERE habit_id IN (SELECT id FROM owned) AND user_id = $userId AND day = $day
      )
      SELECT count(*) FROM owned
    """.query[Int].unique.map(_ > 0)
