import { memo, useLayoutEffect, useMemo, useRef } from 'react'
import { addDays, DAYS_WORDS, formatDayMonth, plural } from '../lib/dates.js'
import { colorVar, dayStatus } from '../lib/habits.js'
import { noMouseFocus } from '../lib/noMouseFocus.js'
import { yearLayout } from '../lib/yearGrid.js'
import StarterCard from '../components/StarterCard.jsx'

const STATUS_TEXT = {
  done: 'сделано',
  miss: 'пропуск',
  pending: 'не отмечено',
  none: 'до старта',
  future: 'ещё не наступил',
}

// Самый ранний год среди start_date задач и отметок (не позже текущего).
export function earliestYear(infos, today) {
  let min = today
  for (const { habit, days } of infos) {
    if (habit.start_date < min) min = habit.start_date
    for (const d of days) if (d < min) min = d
  }
  return Number(min.slice(0, 4))
}

function startText(start, year) {
  const label = formatDayMonth(start)
  return Number(start.slice(0, 4)) === year ? `с ${label}` : `с ${label} ${start.slice(0, 4)}`
}

const YearCard = memo(function YearCard({ habit, days, start, today, layout, onToggle }) {
  const info = { days, start }
  const yesterday = addDays(today, -1)
  let done = 0
  let misses = 0
  const cells = layout.cells.map(({ day, label, style }) => {
    const st = dayStatus(day, info, today)
    if (st === 'done') done++
    // Пропуск — только с start_date до вчера включительно; сегодня без отметки не пропуск.
    else if (st === 'miss' && day <= yesterday) misses++
    const title = `${label}: ${STATUS_TEXT[st]}`
    return (
      <button
        key={day}
        type="button"
        className={`cell ${st}`}
        style={style}
        title={title}
        aria-label={title}
        aria-pressed={st === 'done'}
        disabled={st === 'future'}
        onMouseDown={noMouseFocus}
        onClick={() => onToggle(habit.id, day)}
      />
    )
  })

  // На узком экране сетка скроллится; для текущего года показываем сегодняшнюю неделю.
  const scrollRef = useRef(null)
  const isCurrentYear = today >= layout.first && today <= layout.last
  const todayCol = isCurrentYear ? layout.cells.find((c) => c.day === today)?.col : null
  useLayoutEffect(() => {
    const el = scrollRef.current
    if (!el || el.scrollWidth <= el.clientWidth) return
    if (todayCol == null) {
      el.scrollLeft = 0
      return
    }
    const x = ((todayCol - 1) / (layout.weeks + 1)) * el.scrollWidth
    el.scrollLeft = Math.max(0, x - el.clientWidth * 0.75)
  }, [layout, todayCol])

  return (
    <article className="year-card" style={{ '--hc': colorVar(habit.color) }}>
      <header>
        <div>
          <h3><span className="dot" />{habit.name}</h3>
          <p className="meta">{startText(start, layout.year)} · пропусков {misses}</p>
        </div>
        <p className="count"><b>{done}</b> {plural(done, DAYS_WORDS)}</p>
      </header>
      <div className="grid-scroll" ref={scrollRef}>
        <div className="grid" style={{ '--weeks': layout.weeks }}>
          {layout.months.map((m) => (
            <span key={m.name} className="mlabel" style={{ gridColumn: m.col }}>{m.name}</span>
          ))}
          <span className="wlabel" style={{ gridRow: 2 }}>Пн</span>
          <span className="wlabel" style={{ gridRow: 4 }}>Ср</span>
          <span className="wlabel" style={{ gridRow: 6 }}>Пт</span>
          {cells}
        </div>
      </div>
      <footer className="legend">
        <span><i className="cell miss" />Пропущено</span>
        <span><i className="cell done" />Сделано</span>
        <span><i className="cell pending" />Сегодня</span>
      </footer>
    </article>
  )
})

export default function Year({ year, onYear, today, infos, onToggle, onStarter }) {
  const layout = useMemo(() => yearLayout(year), [year])
  const minYear = useMemo(() => earliestYear(infos, today), [infos, today])
  const maxYear = Number(today.slice(0, 4))

  return (
    <section className="screen s-year">
      <div className="screen-head">
        <div>
          <p className="meta">Годовая сетка</p>
          <h1 className="num">{year}</h1>
        </div>
        <div className="year-switch">
          <button
            className="icon"
            type="button"
            aria-label="Предыдущий год"
            disabled={year <= minYear}
            onMouseDown={noMouseFocus}
            onClick={() => onYear(year - 1)}
          >‹</button>
          <button
            className="icon"
            type="button"
            aria-label="Следующий год"
            disabled={year >= maxYear}
            onMouseDown={noMouseFocus}
            onClick={() => onYear(year + 1)}
          >›</button>
        </div>
      </div>
      {infos.length === 0 ? (
        <StarterCard onStarter={onStarter} />
      ) : (
        infos.map(({ habit, days, start }) => (
          <YearCard
            key={habit.id}
            habit={habit}
            days={days}
            start={start}
            today={today}
            layout={layout}
            onToggle={onToggle}
          />
        ))
      )}
    </section>
  )
}
