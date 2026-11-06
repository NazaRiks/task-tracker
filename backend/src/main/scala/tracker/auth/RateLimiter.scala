package tracker.auth

import cats.effect.{IO, Ref}

import scala.concurrent.duration.*

final case class RateRule(limit: Int, window: FiniteDuration)

/** Порядок на login: сначала по IP, затем по IP+email. Лимит IP+email мешает подбирать пароль к
  * одному аккаунту, а лимит IP — перебирать много аккаунтов с одного адреса.
  */
final case class RateLimits(
    loginPerIpEmail: RateRule,
    loginPerIp: RateRule,
    registerPerIp: RateRule
)

object RateLimits:
  val default: RateLimits = RateLimits(
    loginPerIpEmail = RateRule(10, 15.minutes),
    loginPerIp = RateRule(50, 15.minutes),
    registerPerIp = RateRule(10, 1.hour)
  )

enum RateDecision:
  case Allowed
  case Limited(retryAfter: FiniteDuration)

/** Счётчики с фиксированным окном в памяти одного процесса. `Ref.modify` делает проверку и
  * увеличение одной атомарной операцией, поэтому параллельные запросы не обгонят лимит.
  *
  * Ограничения описаны в SECURITY.md: у каждого инстанса свои счётчики, при перезапуске они
  * обнуляются, а на границе окон возможен всплеск до двух лимитов.
  */
final class RateLimiter private (
    state: Ref[IO, Map[String, RateLimiter.Window]],
    now: IO[FiniteDuration],
    maxKeys: Int
):
  import RateLimiter.Window

  def check(key: String, rule: RateRule): IO[RateDecision] =
    now.flatMap { time =>
      state.modify { windows =>
        windows.get(key).filter(_.resetsAt > time) match
          case Some(window) if window.count >= rule.limit =>
            (windows, RateDecision.Limited(window.resetsAt - time))
          case Some(window) =>
            (windows.updated(key, window.copy(count = window.count + 1)), RateDecision.Allowed)
          case None =>
            val fresh = Window(1, time + rule.window)
            if windows.size < maxKeys || windows.contains(key) then
              (windows.updated(key, fresh), RateDecision.Allowed)
            else
              // Память ограничена. Если заполнена живыми счётчиками, запрос отклоняется: при атаке
              // с множества адресов безопаснее отказать, чем перестать считать.
              val live = windows.filter((_, window) => window.resetsAt > time)
              if live.size < maxKeys then (live.updated(key, fresh), RateDecision.Allowed)
              else (live, RateDecision.Limited(rule.window))
      }
    }

  /** Удаляет истёкшие окна: иначе карта росла бы до предела и не уменьшалась. */
  val prune: IO[Unit] =
    now.flatMap(time => state.update(_.filter((_, window) => window.resetsAt > time)))

object RateLimiter:
  private final case class Window(count: Int, resetsAt: FiniteDuration)

  private val DefaultMaxKeys = 100000

  def create(
      now: IO[FiniteDuration] = IO.monotonic,
      maxKeys: Int = DefaultMaxKeys
  ): IO[RateLimiter] =
    Ref[IO].of(Map.empty[String, Window]).map(new RateLimiter(_, now, maxKeys))
