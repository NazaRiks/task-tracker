import { useCallback, useEffect, useLayoutEffect, useRef, useState } from 'react'
import { supabase } from './supabase.js'
import { MAX_NAME, STARTER_HABITS } from './habits.js'

const PAGE = 1000
const HABIT_COLUMNS = 'id, name, color, position, start_date, created_at'

const byPosition = (a, b) => a.position - b.position || a.created_at.localeCompare(b.created_at)

async function fetchHabits() {
  const { data, error } = await supabase
    .from('habits')
    .select(HABIT_COLUMNS)
    .order('position')
    .order('created_at')
  if (error) throw error
  return data
}

// Supabase отдаёт не больше 1000 строк за запрос — читаем постранично, пока не придёт пустая страница
// (так работает и при меньшем лимите max-rows на сервере).
async function fetchCheckins() {
  const map = new Map()
  for (let from = 0; ; ) {
    const { data, error } = await supabase
      .from('checkins')
      .select('habit_id, day')
      .order('habit_id')
      .order('day')
      .range(from, from + PAGE - 1)
    if (error) throw error
    if (!data.length) break
    for (const { habit_id, day } of data) {
      if (!map.has(habit_id)) map.set(habit_id, new Set())
      map.get(habit_id).add(day)
    }
    from += data.length
  }
  return map
}

function withDay(map, habitId, day, on) {
  const cur = map.get(habitId) ?? new Set()
  if (cur.has(day) === on) return map
  const set = new Set(cur)
  if (on) set.add(day)
  else set.delete(day)
  const next = new Map(map)
  next.set(habitId, set)
  return next
}

export function useTrackerData(notify) {
  const [habits, setHabits] = useState([])
  const [checkins, setCheckins] = useState(() => new Map()) // habitId → Set<'YYYY-MM-DD'>
  const [status, setStatus] = useState('loading') // loading | ready | error

  const habitsRef = useRef(habits)
  const checkinsRef = useRef(checkins)
  useLayoutEffect(() => {
    habitsRef.current = habits
    checkinsRef.current = checkins
  })

  const load = useCallback(async () => {
    setStatus('loading')
    try {
      const [hs, map] = await Promise.all([fetchHabits(), fetchCheckins()])
      for (const h of hs) if (!map.has(h.id)) map.set(h.id, new Set())
      setHabits(hs)
      setCheckins(map)
      setStatus('ready')
    } catch {
      setStatus('error')
    }
  }, [])

  useEffect(() => {
    load()
  }, [load])

  // ---------- отметки ----------
  // Для каждой ячейки храним подтверждённое сервером значение и желаемое (последний клик).
  // Один «воркер» на ячейку последовательно доводит сервер до желаемого значения,
  // поэтому быстрые повторные клики не порождают гонок; чётное число кликов может не дать ни одного запроса.
  const cells = useRef(new Map())

  const toggle = useCallback(async (habitId, day) => {
    const key = `${habitId}|${day}`
    let cell = cells.current.get(key)
    if (!cell) {
      const on = checkinsRef.current.get(habitId)?.has(day) ?? false
      cell = { confirmed: on, desired: on, running: false }
      cells.current.set(key, cell)
    }
    const want = !cell.desired
    cell.desired = want
    setCheckins((prev) => withDay(prev, habitId, day, want))
    if (cell.running) return

    cell.running = true
    try {
      while (cell.confirmed !== cell.desired) {
        const target = cell.desired
        // У роли нет права UPDATE на checkins, поэтому upsert только с ignoreDuplicates (ON CONFLICT DO NOTHING).
        const { error } = target
          ? await supabase
              .from('checkins')
              .upsert({ habit_id: habitId, day }, { onConflict: 'habit_id,day', ignoreDuplicates: true })
          : await supabase.from('checkins').delete().eq('habit_id', habitId).eq('day', day)
        if (error) throw error
        cell.confirmed = target
      }
    } catch {
      const confirmed = cell.confirmed
      cell.desired = confirmed
      setCheckins((prev) => withDay(prev, habitId, day, confirmed))
      if (habitsRef.current.some((h) => h.id === habitId)) {
        notify('Не удалось сохранить отметку. Изменение отменено.')
      }
    } finally {
      cell.running = false
      cells.current.delete(key)
    }
  }, [notify])

  // ---------- задачи ----------
  const insertHabits = useCallback(async (rows) => {
    const { data, error } = await supabase.from('habits').insert(rows).select(HABIT_COLUMNS)
    if (error) throw error
    setHabits((prev) => [...prev, ...data].sort(byPosition))
    setCheckins((prev) => {
      const next = new Map(prev)
      for (const h of data) next.set(h.id, new Set())
      return next
    })
  }, [])

  const nextPosition = () => habitsRef.current.reduce((m, h) => Math.max(m, h.position + 1), 0)

  // start_date передаём явно — локальная дата пользователя (current_date в базе — UTC).
  const addHabit = useCallback(async (name, color, today) => {
    const clean = name.trim().slice(0, MAX_NAME)
    if (!clean) return false
    try {
      await insertHabits([{ name: clean, color, position: nextPosition(), start_date: today }])
      return true
    } catch {
      notify('Не удалось добавить задачу.')
      return false
    }
  }, [insertHabits, notify])

  const addStarter = useCallback(async (today) => {
    const base = nextPosition()
    try {
      await insertHabits(STARTER_HABITS.map((h, i) => ({ ...h, position: base + i, start_date: today })))
      return true
    } catch {
      notify('Не удалось добавить стартовый набор.')
      return false
    }
  }, [insertHabits, notify])

  const updateHabit = useCallback(async (id, patch) => {
    const before = habitsRef.current.find((h) => h.id === id)
    if (!before) return
    setHabits((prev) => prev.map((h) => (h.id === id ? { ...h, ...patch } : h)))
    const { error } = await supabase.from('habits').update(patch).eq('id', id)
    if (error) {
      const revert = Object.fromEntries(Object.keys(patch).map((k) => [k, before[k]]))
      setHabits((prev) => prev.map((h) => (h.id === id ? { ...h, ...revert } : h)))
      notify('Не удалось сохранить изменения задачи.')
    }
  }, [notify])

  const renameHabit = useCallback((id, name) => {
    const clean = name.trim().slice(0, MAX_NAME)
    if (clean) return updateHabit(id, { name: clean })
  }, [updateHabit])

  const setHabitColor = useCallback((id, color) => updateHabit(id, { color }), [updateHabit])

  const setHabitStart = useCallback((id, start_date) => updateHabit(id, { start_date }), [updateHabit])

  const moveHabit = useCallback(async (id, dir) => {
    const list = habitsRef.current
    const i = list.findIndex((h) => h.id === id)
    const j = i + dir
    if (i < 0 || j < 0 || j >= list.length) return
    const order = [...list]
    ;[order[i], order[j]] = [order[j], order[i]]
    // Перенумеровываем 0..n-1 и отправляем только изменившиеся позиции.
    const changed = []
    const next = order.map((h, idx) => {
      if (h.position === idx) return h
      changed.push({ id: h.id, position: idx })
      return { ...h, position: idx }
    })
    setHabits(next)
    const results = await Promise.all(
      changed.map((c) => supabase.from('habits').update({ position: c.position }).eq('id', c.id)),
    )
    if (results.some((r) => r.error)) {
      notify('Не удалось изменить порядок.')
      try {
        setHabits(await fetchHabits())
      } catch {
        setHabits(list)
      }
    }
  }, [notify])

  const deleteHabit = useCallback(async (id) => {
    const habit = habitsRef.current.find((h) => h.id === id)
    if (!habit) return
    const days = checkinsRef.current.get(id)
    setHabits((prev) => prev.filter((h) => h.id !== id))
    const { error } = await supabase.from('habits').delete().eq('id', id)
    if (error) {
      setHabits((prev) => [...prev, habit].sort(byPosition))
      if (days) setCheckins((prev) => new Map(prev).set(id, days))
      notify('Не удалось удалить задачу.')
      return
    }
    setCheckins((prev) => {
      const next = new Map(prev)
      next.delete(id)
      return next
    })
  }, [notify])

  return {
    habits,
    checkins,
    status,
    reload: load,
    toggle,
    addHabit,
    addStarter,
    renameHabit,
    setHabitColor,
    setHabitStart,
    moveHabit,
    deleteHabit,
  }
}
