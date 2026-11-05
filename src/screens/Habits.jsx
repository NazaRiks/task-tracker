import { useRef, useState } from 'react'
import { colorVar, MAX_NAME, nextFreeColor } from '../lib/habits.js'
import Swatches from '../components/Swatches.jsx'
import StarterCard from '../components/StarterCard.jsx'

const MIN_START = '2000-01-01'

// Дата старта: черновик сохраняется при уходе из поля или Enter (иначе при наборе года
// с клавиатуры улетали бы промежуточные даты). Пустое или некорректное значение откатывается.
function StartField({ habit, today, onStart }) {
  const [draft, setDraft] = useState(null)
  const value = draft ?? habit.start_date

  function commit() {
    setDraft(null)
    if (draft && draft !== habit.start_date && draft >= MIN_START && draft <= today) {
      onStart(habit.id, draft)
    }
  }

  return (
    <label className="start-field">
      Начало
      <input
        type="date"
        min={MIN_START}
        max={today}
        required
        value={value}
        onChange={(e) => setDraft(e.target.value)}
        onBlur={commit}
        onKeyDown={(e) => {
          if (e.key === 'Enter') e.currentTarget.blur()
        }}
      />
    </label>
  )
}

function HabitEditRow({ habit, today, isFirst, isLast, onRename, onColor, onStart, onMove, onDelete }) {
  const [draft, setDraft] = useState(habit.name)
  const [editing, setEditing] = useState(false)
  const [confirming, setConfirming] = useState(false)
  const cancelled = useRef(false)
  const value = editing ? draft : habit.name

  function commit() {
    setEditing(false)
    if (cancelled.current) {
      cancelled.current = false
      return
    }
    const clean = draft.trim()
    if (clean && clean !== habit.name) onRename(habit.id, clean)
  }

  return (
    <li className="edit-row" style={{ '--hc': colorVar(habit.color) }}>
      <div className="order">
        <button type="button" className="icon" aria-label="Выше" disabled={isFirst} onClick={() => onMove(habit.id, -1)}>↑</button>
        <button type="button" className="icon" aria-label="Ниже" disabled={isLast} onClick={() => onMove(habit.id, 1)}>↓</button>
      </div>
      <div className="edit-main">
        <input
          type="text"
          aria-label="Название"
          maxLength={MAX_NAME}
          value={value}
          onFocus={() => {
            setDraft(habit.name)
            setEditing(true)
          }}
          onChange={(e) => setDraft(e.target.value)}
          onBlur={commit}
          onKeyDown={(e) => {
            if (e.key === 'Enter') e.currentTarget.blur()
            if (e.key === 'Escape') {
              cancelled.current = true
              e.currentTarget.blur()
            }
          }}
        />
        <div className="edit-extra">
          <Swatches value={habit.color} onChange={(c) => onColor(habit.id, c)} />
          <StartField habit={habit} today={today} onStart={onStart} />
        </div>
      </div>
      {confirming ? (
        <div className="confirm">
          <span>Удалить вместе с отметками?</span>
          <button type="button" className="btn danger" onClick={() => onDelete(habit.id)}>Удалить</button>
          <button type="button" className="btn ghost" onClick={() => setConfirming(false)} autoFocus>Отмена</button>
        </div>
      ) : (
        <button type="button" className="btn ghost del" onClick={() => setConfirming(true)}>Удалить</button>
      )}
    </li>
  )
}

function AddHabitForm({ habits, onAdd }) {
  const [name, setName] = useState('')
  const [color, setColor] = useState(null)
  const [busy, setBusy] = useState(false)
  const current = color ?? nextFreeColor(habits)

  async function submit(e) {
    e.preventDefault()
    if (busy || !name.trim()) return
    setBusy(true)
    const ok = await onAdd(name, current)
    setBusy(false)
    if (ok) {
      setName('')
      setColor(null)
    }
  }

  return (
    <form className="card add-row" onSubmit={submit} style={{ '--hc': colorVar(current) }}>
      <input
        type="text"
        placeholder="Новая задача, например «Чтение»"
        aria-label="Название новой задачи"
        maxLength={MAX_NAME}
        value={name}
        onChange={(e) => setName(e.target.value)}
      />
      <Swatches value={current} onChange={setColor} />
      <button className="btn" type="submit" disabled={busy || !name.trim()}>Добавить</button>
    </form>
  )
}

export default function Habits({ habits, today, onAdd, onStarter, onRename, onColor, onStart, onMove, onDelete }) {
  return (
    <section className="screen s-habits">
      <div className="screen-head">
        <div>
          <p className="meta">Что отслеживаю</p>
          <h1>Задачи</h1>
        </div>
      </div>
      {habits.length === 0 ? (
        <StarterCard onStarter={onStarter} />
      ) : (
        <ul className="edit-list">
          {habits.map((h, i) => (
            <HabitEditRow
              key={h.id}
              habit={h}
              today={today}
              isFirst={i === 0}
              isLast={i === habits.length - 1}
              onRename={onRename}
              onColor={onColor}
              onStart={onStart}
              onMove={onMove}
              onDelete={onDelete}
            />
          ))}
        </ul>
      )}
      <AddHabitForm habits={habits} onAdd={onAdd} />
    </section>
  )
}
