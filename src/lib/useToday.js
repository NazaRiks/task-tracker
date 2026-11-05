import { useEffect, useState } from 'react'
import { toKey } from './dates.js'

// Текущая локальная дата (YYYY-MM-DD). Обновляется после полуночи без перезагрузки:
// таймер до следующей полуночи + проверка раз в минуту и при возвращении на вкладку
// (в фоне браузер может «усыплять» таймеры, а компьютер — уходить в сон).
export function useToday() {
  const [today, setToday] = useState(() => toKey(new Date()))

  useEffect(() => {
    let timer
    const check = () => setToday((prev) => {
      const key = toKey(new Date())
      return key === prev ? prev : key
    })
    const schedule = () => {
      const now = new Date()
      const next = new Date(now.getFullYear(), now.getMonth(), now.getDate() + 1, 0, 0, 1)
      timer = setTimeout(() => {
        check()
        schedule()
      }, next - now)
    }
    const onVisible = () => {
      if (document.visibilityState === 'visible') check()
    }

    schedule()
    const interval = setInterval(check, 60_000)
    document.addEventListener('visibilitychange', onVisible)
    window.addEventListener('focus', check)
    return () => {
      clearTimeout(timer)
      clearInterval(interval)
      document.removeEventListener('visibilitychange', onVisible)
      window.removeEventListener('focus', check)
    }
  }, [])

  return today
}
