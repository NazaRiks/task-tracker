// Проверка правил статистики на граничных случаях (см. CLAUDE.md). Запуск: npm run check
import assert from 'node:assert/strict'
import { addDays } from '../src/lib/dates.js'
import { currentStreak, dayStatus, earliestYear, upcomingStartText } from '../src/lib/habits.js'
import { computeStats, canShift, periodLabel, periodRange, shiftPeriod } from '../src/lib/stats.js'

const T = '2026-11-05'
const range = (a, b) => { const r = []; for (let d = a; d <= b; d = addDays(d, 1)) r.push(d); return r }
const H = (start, marks) => ({ habit: { id: start }, start, days: new Set(marks) })
let n = 0
const ok = (name, fn) => { fn(); n++; console.log('✓', name) }

// A: старт посреди периода + отметки до старта
const A = H('2026-10-15', ['2026-10-10', '2026-10-11', ...range('2026-10-15', '2026-10-20'), T])
ok('старт посреди месяца, отметки до старта не входят в %', () => {
  const s = computeStats('month', '2026-10-03', [A], T)
  assert.equal(s.possible, 17)
  assert.equal(s.done, 6)
  assert.equal(s.marks, 8)
  assert.equal(s.pct, 35)
  assert.equal(s.rows[0].pct, 35)
})
ok('текущий месяц: сегодня отмечено → входит в возможные', () => {
  const s = computeStats('month', T, [A], T)
  assert.deepEqual([s.possible, s.done, s.pct], [5, 1, 20])
})
ok('текущий месяц: сегодня не отмечено → не входит', () => {
  const B = H('2026-10-15', range('2026-10-15', '2026-10-20'))
  const s = computeStats('month', T, [B], T)
  assert.deepEqual([s.possible, s.done, s.pct], [4, 0, 0])
})
ok('задача стартовала сегодня и не отмечена → возможных 0, «—»', () => {
  const s = computeStats('week', T, [H(T, [])], T)
  assert.deepEqual([s.possible, s.pct, s.perfectOf], [0, null, 0])
})

// B: пустой период
ok('нет задач → 0 возможных, pct null, идеальных 0 из 0', () => {
  const s = computeStats('month', '2026-10-01', [], T)
  assert.deepEqual([s.possible, s.done, s.marks, s.pct, s.perfect, s.perfectOf], [0, 0, 0, null, 0, 0])
  assert.ok(s.bars.every((b) => b.value === null))
})
ok('период целиком до старта: только «Отметок», без %', () => {
  const C = H('2026-10-15', ['2026-09-30'])
  const s = computeStats('month', '2026-09-10', [C], T)
  assert.deepEqual([s.possible, s.done, s.marks, s.pct, s.perfectOf], [0, 0, 1, null, 0])
})

// C: период в будущем
ok('будущий период пустой, листать вперёд из текущего нельзя', () => {
  const s = computeStats('month', '2026-12-01', [A], T)
  assert.deepEqual([s.possible, s.marks, s.pct, s.perfectOf], [0, 0, null, 0])
  assert.ok(s.bars.every((b) => b.value === null))
  assert.equal(canShift('month', T, 1, T, 2026), false)
  assert.equal(canShift('week', T, 1, T, 2026), false)
  assert.equal(canShift('year', T, 1, T, 2026), false)
  assert.equal(canShift('month', '2026-10-01', 1, T, 2026), true)
})
ok('назад — не раньше самого раннего года', () => {
  assert.equal(canShift('month', '2026-01-15', -1, T, 2026), false)
  assert.equal(canShift('month', '2026-02-15', -1, T, 2026), true)
  assert.equal(canShift('week', '2026-01-01', -1, T, 2026), false) // неделя 29.12.2025–04.01.2026
  assert.equal(canShift('week', '2026-01-05', -1, T, 2026), true) // предыдущая заходит в 2026
  assert.equal(canShift('year', '2026-06-01', -1, T, 2025), true)
})

// Идеальные дни
const H1 = H('2026-10-01', range('2026-10-01', '2026-10-20'))
const H2 = H('2026-10-15', range('2026-10-15', '2026-10-17'))
ok('идеальные дни учитывают только активные задачи', () => {
  const s = computeStats('month', '2026-10-01', [H1, H2], T)
  assert.deepEqual([s.perfect, s.perfectOf], [17, 31])
  assert.equal(s.bars[0].value, 1) // 1 окт: активна только H1
  assert.equal(s.bars[17].value, 0.5) // 18 окт: 1 из 2
  assert.equal(s.bars[0].full, true)
})
ok('сегодня в идеальных — только если всё отмечено', () => {
  const X = H('2026-11-01', range('2026-11-01', T))
  const Y = H('2026-11-01', range('2026-11-01', '2026-11-04'))
  let s = computeStats('week', T, [X, Y], T) // неделя 2–8 ноября
  assert.deepEqual([s.perfect, s.perfectOf], [3, 3])
  const Y2 = H('2026-11-01', range('2026-11-01', T))
  s = computeStats('week', T, [X, Y2], T)
  assert.deepEqual([s.perfect, s.perfectOf], [4, 4])
  assert.equal(s.bars[3].value, 1) // сегодня (чт)
  assert.equal(s.bars[4].value, null) // будущее (пт)
})
ok('дни без активных задач не считаются', () => {
  const s = computeStats('month', '2026-10-01', [H('2026-10-30', [])], T)
  assert.equal(s.perfectOf, 2)
})

// Год
ok('год: 12 столбиков, будущие и пустые месяцы — null', () => {
  const s = computeStats('year', T, [A], T)
  assert.equal(s.bars.length, 12)
  assert.ok(s.bars.slice(0, 9).every((b) => b.value === null))
  assert.equal(Math.round(s.bars[9].value * 100), 35)
  assert.equal(s.bars[10].value, 1 / 5)
  assert.equal(s.bars[11].value, null)
  assert.deepEqual([s.possible, s.done], [22, 7])
})
ok('% никогда не больше 100 даже при отметках до старта', () => {
  const D = H('2026-10-20', range('2026-10-01', '2026-10-31'))
  const s = computeStats('month', '2026-10-01', [D], T)
  assert.deepEqual([s.possible, s.done, s.marks, s.pct], [12, 12, 31, 100])
})

// Задача с будущим стартом
ok('будущий старт без отметок: возможных 0, «—», идеальных нет', () => {
  const F = H('2026-12-01', [])
  for (const kind of ['week', 'month', 'year']) {
    const s = computeStats(kind, T, [F], T)
    assert.deepEqual([s.possible, s.done, s.marks, s.pct, s.perfect, s.perfectOf], [0, 0, 0, null, 0, 0])
    assert.equal(s.rows[0].pct, null)
    assert.ok(s.bars.every((b) => b.value === null))
  }
})
ok('будущий старт с отметками: только «Отметок» и серия', () => {
  const F = H('2026-11-06', ['2026-11-04', T])
  const s = computeStats('week', T, [F], T)
  assert.deepEqual([s.possible, s.done, s.marks, s.pct, s.perfectOf], [0, 0, 2, null, 0])
  assert.ok(s.bars.every((b) => b.value === null))
  assert.equal(currentStreak(F.days, T), 2)
})
ok('будущий старт не портит расчёт других задач', () => {
  const X = H('2026-11-02', range('2026-11-02', T))
  const s = computeStats('week', T, [X, H('2026-12-01', [])], T)
  assert.deepEqual([s.possible, s.done, s.pct, s.perfect, s.perfectOf], [4, 4, 100, 4, 4])
  assert.equal(s.rows[1].pct, null)
  assert.equal(s.bars[3].full, true)
})
ok('будущий старт: статусы дней, граница листания, подпись', () => {
  const info = { start: '2026-12-01', days: new Set(['2026-11-04']) }
  assert.equal(dayStatus(T, info, T), 'none')
  assert.equal(dayStatus('2026-11-03', info, T), 'none')
  assert.equal(dayStatus('2026-11-04', info, T), 'done')
  assert.equal(earliestYear([{ habit: { start_date: '2027-03-01' }, days: new Set() }], T), 2026)
  assert.equal(upcomingStartText('2026-11-06', T), 'завтра')
  assert.equal(upcomingStartText('2026-11-12', T), '12 ноября')
  assert.equal(upcomingStartText('2027-01-12', T), '12 января 2027')
  assert.equal(upcomingStartText('2027-01-01', '2026-12-31'), 'завтра')
})

// Периоды и подписи
ok('границы и подписи периодов', () => {
  assert.deepEqual(periodRange('week', '2026-11-01'), { start: '2026-10-26', end: '2026-11-01' })
  assert.deepEqual(periodRange('month', '2024-02-10'), { start: '2024-02-01', end: '2024-02-29' })
  assert.equal(periodLabel('week', T), '2–8 ноября 2026')
  assert.equal(periodLabel('week', '2026-11-01'), '26 октября – 1 ноября 2026')
  assert.equal(periodLabel('week', '2026-01-01'), '29 декабря 2025 – 4 января 2026')
  assert.equal(periodLabel('month', '2026-10-31'), 'Октябрь 2026')
  assert.equal(shiftPeriod('month', '2026-01-31', -1), '2025-12-01')
  assert.equal(shiftPeriod('week', '2026-03-29', 1), '2026-03-30') // переход на летнее время
})

console.log(`\nВсе проверки пройдены: ${n}`)
