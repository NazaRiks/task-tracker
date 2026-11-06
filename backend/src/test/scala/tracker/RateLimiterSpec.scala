package tracker

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import tracker.auth.{RateDecision, RateLimiter, RateRule}

import scala.concurrent.duration.*

class RateLimiterSpec extends munit.CatsEffectSuite:
  private val rule = RateRule(2, 1.minute)

  private def limiterAt(maxKeys: Int = 100): IO[(Ref[IO, FiniteDuration], RateLimiter)] =
    for
      clock <- Ref[IO].of(0.seconds)
      limiter <- RateLimiter.create(clock.get, maxKeys)
    yield (clock, limiter)

  test("лимит срабатывает на следующей попытке, Retry-After равен остатку окна") {
    for
      (clock, limiter) <- limiterAt()
      first <- limiter.check("k", rule)
      second <- limiter.check("k", rule)
      _ <- clock.set(20.seconds)
      third <- limiter.check("k", rule)
    yield
      assertEquals(first, RateDecision.Allowed)
      assertEquals(second, RateDecision.Allowed)
      assertEquals(third, RateDecision.Limited(40.seconds))
  }

  test("после окончания окна счётчик начинается заново") {
    for
      (clock, limiter) <- limiterAt()
      _ <- limiter.check("k", rule).replicateA(3)
      _ <- clock.set(61.seconds)
      after <- limiter.check("k", rule)
    yield assertEquals(after, RateDecision.Allowed)
  }

  test("параллельные запросы не обгоняют лимит") {
    for
      (_, limiter) <- limiterAt()
      decisions <- List.fill(200)(limiter.check("k", rule)).parSequence
    yield assertEquals(decisions.count(_ == RateDecision.Allowed), 2)
  }

  test("память ограничена: новые ключи отклоняются, пока всё занято живыми окнами") {
    for
      (clock, limiter) <- limiterAt(maxKeys = 2)
      _ <- limiter.check("a", rule)
      _ <- limiter.check("b", rule)
      full <- limiter.check("c", rule)
      known <- limiter.check("a", rule)
      _ <- clock.set(61.seconds)
      afterExpiry <- limiter.check("c", rule)
    yield
      assertEquals(full, RateDecision.Limited(1.minute))
      assertEquals(known, RateDecision.Allowed)
      assertEquals(afterExpiry, RateDecision.Allowed)
  }

  test("prune удаляет только истёкшие окна") {
    val shortRule = RateRule(1, 10.seconds)
    for
      (clock, limiter) <- limiterAt()
      _ <- limiter.check("old", shortRule)
      _ <- clock.set(5.seconds)
      _ <- limiter.check("new", shortRule)
      _ <- clock.set(11.seconds)
      _ <- limiter.prune
      stillLimited <- limiter.check("new", shortRule)
      oldIsFresh <- limiter.check("old", shortRule)
    yield
      assert(stillLimited.isInstanceOf[RateDecision.Limited])
      assertEquals(oldIsFresh, RateDecision.Allowed)
  }
