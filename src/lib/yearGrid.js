import { addDays, formatDayMonth, MONTHS_SHORT, toKey, weekdayMon0 } from './dates.js'

// Последний год, до которого можно листать вперёд на экране «Год» (включительно).
export const MAX_YEAR = 2030

// Раскладка годовой сетки: колонки — недели с понедельника, строки — дни недели.
// В сетке: строка 1 — подписи месяцев, строки 2–8 — Пн…Вс; колонка 1 — подписи дней, недели с колонки 2.
// Зависит только от года, поэтому считается один раз на год и общая для всех карточек.
export function yearLayout(year) {
  const first = toKey(new Date(year, 0, 1))
  const last = toKey(new Date(year, 11, 31))
  const offset = weekdayMon0(first) // сколько дней недели до 1 января
  const cells = []
  const months = []
  let i = 0
  for (let day = first; day <= last; day = addDays(day, 1), i++) {
    const pos = offset + i
    const col = Math.floor(pos / 7) + 2
    const row = (pos % 7) + 2
    if (day.endsWith('-01')) months.push({ col, name: MONTHS_SHORT[Number(day.slice(5, 7)) - 1] })
    cells.push({ day, col, label: formatDayMonth(day), style: { gridArea: `${row}/${col}` } })
  }
  const weeks = Math.floor((offset + cells.length - 1) / 7) + 1
  return { year, first, last, weeks, cells, months }
}
