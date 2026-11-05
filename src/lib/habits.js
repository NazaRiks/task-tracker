import { addDays } from './dates.js'

// Палитра задач (без красного — красный означает пропуск). В базе хранится имя, в UI — var(--h-<имя>).
export const PALETTE = [
  { id: 'green', label: 'Зелёный' },
  { id: 'blue', label: 'Синий' },
  { id: 'violet', label: 'Фиолетовый' },
  { id: 'amber', label: 'Жёлтый' },
  { id: 'teal', label: 'Бирюзовый' },
  { id: 'pink', label: 'Розовый' },
  { id: 'orange', label: 'Оранжевый' },
  { id: 'slate', label: 'Серый' },
]
const COLOR_IDS = new Set(PALETTE.map((c) => c.id))

export function colorVar(color) {
  return `var(--h-${COLOR_IDS.has(color) ? color : 'green'})`
}

// Первый цвет палитры, который ещё не занят; если заняты все — по кругу.
export function nextFreeColor(habits) {
  const used = new Set(habits.map((h) => h.color))
  return (PALETTE.find((c) => !used.has(c.id)) ?? PALETTE[habits.length % PALETTE.length]).id
}

export const STARTER_HABITS = [
  { name: 'Программирование', color: 'green' },
  { name: 'Словенский', color: 'blue' },
  { name: 'Английский', color: 'violet' },
  { name: 'Зал', color: 'amber' },
]

export const MAX_NAME = 80

// Состояние дня: future | done | none (до старта, без отметки) | pending (сегодня, не отмечено) | miss.
// Старт — только habit.start_date: отметки раньше него считаются «сделано», но пропусков до старта нет.
export function dayStatus(day, { days, start }, today) {
  if (day > today) return 'future'
  if (days.has(day)) return 'done'
  if (day < start) return 'none'
  if (day === today) return 'pending'
  return 'miss'
}

// Текущая серия: неотмеченный сегодняшний день серию не обрывает.
export function currentStreak(days, today) {
  let d = days.has(today) ? today : addDays(today, -1)
  let n = 0
  while (days.has(d)) {
    n++
    d = addDays(d, -1)
  }
  return n
}

export function bestStreak(days) {
  const sorted = [...days].sort()
  let best = 0
  let run = 0
  let prev = null
  for (const d of sorted) {
    run = prev && addDays(prev, 1) === d ? run + 1 : 1
    if (run > best) best = run
    prev = d
  }
  return best
}

// Сводка по задаче для экранов.
export function habitInfo(habit, days, today) {
  return {
    habit,
    days,
    start: habit.start_date,
    streak: currentStreak(days, today),
    best: bestStreak(days),
  }
}

// Самый ранний год среди start_date задач и отметок (не позже текущего) — граница листания назад.
export function earliestYear(infos, today) {
  let min = today
  for (const { habit, days } of infos) {
    if (habit.start_date < min) min = habit.start_date
    for (const d of days) if (d < min) min = d
  }
  return Number(min.slice(0, 4))
}
