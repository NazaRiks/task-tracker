import { addDays, daysText, formatDayMonth, formatLongDay, WEEKDAY_LETTERS, weekdayMon0 } from '../lib/dates.js'
import { colorVar, dayStatus } from '../lib/habits.js'
import StarterCard from '../components/StarterCard.jsx'

const STATUS_TEXT = {
  done: 'сделано',
  miss: 'пропуск',
  pending: 'не отмечено',
  none: 'до старта',
}

// Клик мышью не передаёт кнопке фокус — рамка остаётся только для клавиатуры (:focus-visible).
const noMouseFocus = (e) => e.preventDefault()

function CheckIcon() {
  return (
    <svg viewBox="0 0 24 24" aria-hidden="true">
      <path d="M5 12.5l4.5 4.5L19 7.5" />
    </svg>
  )
}

export default function Today({ today, infos, onToggle, onStarter, onGoHabits }) {
  const total = infos.length
  const done = infos.filter((x) => x.days.has(today)).length
  const pct = total ? Math.round((done / total) * 100) : 0
  const week = Array.from({ length: 7 }, (_, i) => addDays(today, i - 6))

  return (
    <section className="screen s-today">
      <div className="screen-head">
        <div>
          <p className="meta">Сегодня</p>
          <h1>{formatLongDay(today)}</h1>
        </div>
        {total > 0 && (
          <div className="progress-pill" style={{ '--p': pct }}>
            <span className="ring" />
            <span><b className="num">{done}</b> из {total} сделано</span>
          </div>
        )}
      </div>

      {total === 0 ? (
        <StarterCard onStarter={onStarter}>
          <button type="button" className="btn ghost" onClick={onGoHabits}>Свои задачи</button>
        </StarterCard>
      ) : (
        <>
          <div className="list">
            {infos.map((info) => {
              const { habit, days, streak, best } = info
              const on = days.has(today)
              return (
                <article key={habit.id} className="habit-row" style={{ '--hc': colorVar(habit.color) }}>
                  <button
                    className={on ? 'check on' : 'check'}
                    type="button"
                    aria-pressed={on}
                    aria-label={`${habit.name}: ${on ? 'сделано' : 'отметить'}`}
                    onClick={() => onToggle(habit.id, today)}
                    onMouseDown={noMouseFocus}
                  >
                    <CheckIcon />
                  </button>
                  <div className="habit-main">
                    <h3>{habit.name}</h3>
                    <p className="meta">Серия: <b>{daysText(streak)}</b> · рекорд {daysText(best)}</p>
                  </div>
                  <div className="strip" aria-label="Последние 7 дней">
                    {week.map((day) => {
                      const st = dayStatus(day, info, today)
                      const title = `${formatDayMonth(day)}: ${STATUS_TEXT[st]}`
                      return (
                        <button
                          key={day}
                          type="button"
                          className={st === 'none' ? 'mini' : `mini ${st}`}
                          title={title}
                          aria-label={title}
                          aria-pressed={st === 'done'}
                          onClick={() => onToggle(habit.id, day)}
                          onMouseDown={noMouseFocus}
                        >
                          <i>{WEEKDAY_LETTERS[weekdayMon0(day)]}</i>
                        </button>
                      )
                    })}
                  </div>
                </article>
              )
            })}
          </div>
          <p className="hint">Квадратики справа — последние 7 дней. Нажми на любой, чтобы исправить отметку задним числом.</p>
        </>
      )}
    </section>
  )
}
