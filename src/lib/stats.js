import {
  addDays, formatDayMonth, fromKey, MONTHS, MONTHS_GEN, MONTHS_SHORT, toKey, WEEKDAY_LETTERS, weekdayMon0,
} from './dates.js'

// Правила расчётов — см. CLAUDE.md, раздел «Статистика». Все функции чистые (для проверки скриптом).

export const PERIODS = [
  { id: 'week', label: 'Неделя' },
  { id: 'month', label: 'Месяц' },
  { id: 'year', label: 'Год' },
]

// Границы периода, содержащего дату anchor.
export function periodRange(kind, anchor) {
  const d = fromKey(anchor)
  if (kind === 'week') {
    const start = addDays(anchor, -weekdayMon0(anchor))
    return { start, end: addDays(start, 6) }
  }
  if (kind === 'month') {
    return {
      start: toKey(new Date(d.getFullYear(), d.getMonth(), 1)),
      end: toKey(new Date(d.getFullYear(), d.getMonth() + 1, 0)),
    }
  }
  return { start: `${d.getFullYear()}-01-01`, end: `${d.getFullYear()}-12-31` }
}

// Начало соседнего периода (dir = -1 / +1).
export function shiftPeriod(kind, anchor, dir) {
  const { start } = periodRange(kind, anchor)
  const d = fromKey(start)
  if (kind === 'week') return addDays(start, 7 * dir)
  if (kind === 'month') return toKey(new Date(d.getFullYear(), d.getMonth() + dir, 1))
  return `${d.getFullYear() + dir}-01-01`
}

// Вперёд — не дальше текущего периода, назад — не раньше самого раннего года.
export function canShift(kind, anchor, dir, today, minYear) {
  const next = periodRange(kind, shiftPeriod(kind, anchor, dir))
  return dir > 0 ? next.start <= today : next.end >= `${minYear}-01-01`
}

export function periodLabel(kind, anchor) {
  const { start, end } = periodRange(kind, anchor)
  const s = fromKey(start)
  const e = fromKey(end)
  if (kind === 'year') return String(s.getFullYear())
  if (kind === 'month') return `${MONTHS[s.getMonth()]} ${s.getFullYear()}`
  if (s.getFullYear() !== e.getFullYear()) {
    return `${formatDayMonth(start)} ${s.getFullYear()} – ${formatDayMonth(end)} ${e.getFullYear()}`
  }
  if (s.getMonth() !== e.getMonth()) return `${formatDayMonth(start)} – ${formatDayMonth(end)} ${e.getFullYear()}`
  return `${s.getDate()}–${e.getDate()} ${MONTHS_GEN[e.getMonth()]} ${e.getFullYear()}`
}

function eachDay(from, to, fn) {
  for (let d = from; d <= to; d = addDays(d, 1)) fn(d)
}

// Одна задача за отрезок [from, to]:
// possible — дни от max(start, from) до min(to, вчера) + сегодня, если отмечено;
// done — отметки внутри possible (для %), marks — все отметки отрезка, включая до старта.
export function habitPeriod({ start, days }, from, to, today) {
  let possible = 0
  let done = 0
  let marks = 0
  const last = to < today ? to : today
  eachDay(from, last, (d) => {
    const has = days.has(d)
    if (has) marks++
    if (d < start) return
    if (d === today) {
      if (has) {
        possible++
        done++
      }
      return
    }
    possible++
    if (has) done++
  })
  return { possible, done, marks }
}

export const pctOf = (done, possible) => (possible > 0 ? Math.round((done / possible) * 100) : null)

export function computeStats(kind, anchor, infos, today) {
  const { start, end } = periodRange(kind, anchor)

  const rows = infos.map((info) => {
    const r = habitPeriod(info, start, end, today)
    return { info, ...r, pct: pctOf(r.done, r.possible) }
  })
  const possible = rows.reduce((s, r) => s + r.possible, 0)
  const done = rows.reduce((s, r) => s + r.done, 0)
  const marks = rows.reduce((s, r) => s + r.marks, 0)

  // Идеальные дни: все активные в этот день задачи (start <= день) отмечены.
  // Дни до вчера учитываются всегда, сегодня — только если уже всё отмечено; дни без активных задач — нет.
  let perfect = 0
  let perfectOf = 0
  const dayBars = []
  eachDay(start, end, (d) => {
    const bar = { key: d, value: null, full: false }
    if (d <= today) {
      const active = infos.filter((x) => x.start <= d)
      if (active.length) {
        const n = active.filter((x) => x.days.has(d)).length
        const all = n === active.length
        if (d < today || all) {
          perfectOf++
          if (all) perfect++
        }
        bar.value = n / active.length
        bar.full = all
        bar.title = `${formatDayMonth(d)}: ${n} из ${active.length}`
      }
    }
    if (!bar.title) bar.title = `${formatDayMonth(d)}: ${d > today ? 'ещё не наступил' : 'нет задач'}`
    dayBars.push(bar)
  })

  let bars
  if (kind === 'year') {
    const year = Number(start.slice(0, 4))
    bars = MONTHS.map((name, m) => {
      const r = periodRange('month', toKey(new Date(year, m, 1)))
      const bar = { key: r.start, label: MONTHS_SHORT[m], value: null, full: false }
      if (r.start <= today) {
        let p = 0
        let dn = 0
        for (const info of infos) {
          const x = habitPeriod(info, r.start, r.end, today)
          p += x.possible
          dn += x.done
        }
        if (p > 0) {
          bar.value = dn / p
          bar.full = dn === p
        }
      }
      const pct = bar.value == null ? null : Math.round(bar.value * 100)
      bar.title = `${name}: ${pct == null ? (r.start > today ? 'ещё не наступил' : 'нет данных') : `${pct}%`}`
      return bar
    })
  } else {
    bars = dayBars.map((b) => {
      const day = Number(b.key.slice(8))
      const label = kind === 'week'
        ? WEEKDAY_LETTERS[weekdayMon0(b.key)]
        : (day - 1) % 7 === 0 ? String(day) : ''
      return { ...b, label }
    })
  }

  return {
    start,
    end,
    possible,
    done,
    marks,
    pct: pctOf(done, possible),
    perfect,
    perfectOf,
    bars,
    rows,
  }
}
