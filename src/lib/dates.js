// Все даты — локальные, в формате YYYY-MM-DD. toISOString() не используем: он даёт UTC и сдвигает день.

const pad = (n) => String(n).padStart(2, '0')

export function toKey(date) {
  return `${date.getFullYear()}-${pad(date.getMonth() + 1)}-${pad(date.getDate())}`
}

export function fromKey(key) {
  const [y, m, d] = key.split('-').map(Number)
  return new Date(y, m - 1, d)
}

export function addDays(key, n) {
  const [y, m, d] = key.split('-').map(Number)
  return toKey(new Date(y, m - 1, d + n))
}

// 0 — понедельник … 6 — воскресенье
export function weekdayMon0(key) {
  return (fromKey(key).getDay() + 6) % 7
}

export const MONTHS_GEN = [
  'января', 'февраля', 'марта', 'апреля', 'мая', 'июня',
  'июля', 'августа', 'сентября', 'октября', 'ноября', 'декабря',
]
export const MONTHS = [
  'Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь',
]
export const MONTHS_SHORT = ['Янв', 'Фев', 'Мар', 'Апр', 'Май', 'Июн', 'Июл', 'Авг', 'Сен', 'Окт', 'Ноя', 'Дек']
export const WEEKDAYS = ['Понедельник', 'Вторник', 'Среда', 'Четверг', 'Пятница', 'Суббота', 'Воскресенье']
export const WEEKDAY_LETTERS = ['П', 'В', 'С', 'Ч', 'П', 'С', 'В']

// «5 ноября»
export function formatDayMonth(key) {
  const d = fromKey(key)
  return `${d.getDate()} ${MONTHS_GEN[d.getMonth()]}`
}

// «Четверг, 5 ноября»
export function formatLongDay(key) {
  return `${WEEKDAYS[weekdayMon0(key)]}, ${formatDayMonth(key)}`
}

// plural(5, ['день', 'дня', 'дней']) → 'дней'
export function plural(n, [one, few, many]) {
  const a = Math.abs(n) % 100
  const b = a % 10
  if (a > 10 && a < 20) return many
  if (b === 1) return one
  if (b >= 2 && b <= 4) return few
  return many
}

export const DAYS_WORDS = ['день', 'дня', 'дней']
export const daysText = (n) => `${n} ${plural(n, DAYS_WORDS)}`
