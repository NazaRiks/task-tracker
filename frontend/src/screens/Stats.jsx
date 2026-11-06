import { useMemo } from 'react'
import { colorVar, earliestYear } from '../lib/habits.js'
import { noMouseFocus } from '../lib/noMouseFocus.js'
import { canShift, computeStats, periodLabel, PERIODS, shiftPeriod } from '../lib/stats.js'
import StarterCard from '../components/StarterCard.jsx'

const pctText = (pct) => (pct == null ? '—' : `${pct}%`)

export default function Stats({ period, onPeriod, today, infos, onStarter }) {
  const { kind, anchor } = period
  const minYear = useMemo(() => earliestYear(infos, today), [infos, today])
  const s = useMemo(() => computeStats(kind, anchor, infos, today), [kind, anchor, infos, today])
  const canPrev = canShift(kind, anchor, -1, today, minYear)
  const canNext = canShift(kind, anchor, 1, today, minYear)

  return (
    <section className="screen s-stats">
      <div className="screen-head">
        <div>
          <p className="meta">Статистика</p>
          <h1>{periodLabel(kind, anchor)}</h1>
        </div>
        <div className="period">
          <div className="seg" role="group" aria-label="Период">
            {PERIODS.map((p) => (
              <button
                key={p.id}
                type="button"
                className={p.id === kind ? 'on' : undefined}
                aria-pressed={p.id === kind}
                onMouseDown={noMouseFocus}
                onClick={() => onPeriod({ kind: p.id, anchor })}
              >
                {p.label}
              </button>
            ))}
          </div>
          <button
            className="icon"
            type="button"
            aria-label="Назад"
            disabled={!canPrev}
            onMouseDown={noMouseFocus}
            onClick={() => onPeriod({ kind, anchor: shiftPeriod(kind, anchor, -1) })}
          >‹</button>
          <button
            className="icon"
            type="button"
            aria-label="Вперёд"
            disabled={!canNext}
            onMouseDown={noMouseFocus}
            onClick={() => onPeriod({ kind, anchor: shiftPeriod(kind, anchor, 1) })}
          >›</button>
        </div>
      </div>

      {infos.length === 0 ? (
        <StarterCard onStarter={onStarter} />
      ) : (
        <>
          <div className="tiles">
            <div className="card tile">
              <span className="k">Выполнение</span>
              <span className="v">{s.pct == null ? '—' : <>{s.pct}<small>%</small></>}</span>
            </div>
            <div className="card tile">
              <span className="k">Отметок</span>
              <span className="v">{s.marks}<small> / {s.possible}</small></span>
            </div>
            <div className="card tile">
              <span className="k">Идеальных дней</span>
              <span className="v">{s.perfect}<small> из {s.perfectOf}</small></span>
            </div>
          </div>

          <div className="card chart-card">
            <h2>{kind === 'year' ? 'По месяцам' : 'По дням'}</h2>
            <div className="chart-scroll">
              <div className="bars" style={{ gridTemplateColumns: `repeat(${s.bars.length}, minmax(9px, 1fr))` }}>
                {s.bars.map((b) => (
                  <div key={b.key} className={b.full ? 'bar full' : 'bar'} title={b.title}>
                    <span style={{ height: `${b.value == null ? 0 : b.value * 100}%` }} />
                    <i>{b.label}</i>
                  </div>
                ))}
              </div>
            </div>
            <div className="chart-legend">
              <span><i style={{ background: 'var(--brand)' }} />Все задачи сделаны</span>
              <span><i style={{ background: 'color-mix(in srgb, var(--brand) 55%, var(--empty))' }} />Часть задач</span>
            </div>
          </div>

          <div className="card table-card">
            <h2>По задачам</h2>
            <div className="table-scroll">
              <div className="shead">
                <span>Задача</span><span /><span>%</span><span>Дни</span><span>Серия</span><span>Рекорд</span>
              </div>
              {s.rows.map(({ info, done, possible, pct }) => (
                <div key={info.habit.id} className="srow" style={{ '--hc': colorVar(info.habit.color) }}>
                  <div className="sname"><span className="dot" />{info.habit.name}</div>
                  <div className="sbar"><span style={{ width: `${pct ?? 0}%` }} /></div>
                  <div className="spct num">{pctText(pct)}</div>
                  <div className="sfig num">{done}<span>/{possible}</span></div>
                  <div className="sfig num">{info.streak}</div>
                  <div className="sfig num">{info.best}</div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </section>
  )
}
