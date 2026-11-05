import { useState } from 'react'
import { STARTER_HABITS } from '../lib/habits.js'

// Показывается, когда задач нет.
export default function StarterCard({ onStarter, children }) {
  const [busy, setBusy] = useState(false)

  async function add() {
    setBusy(true)
    await onStarter()
    setBusy(false)
  }

  return (
    <div className="card empty">
      <h2>Задач пока нет</h2>
      <p className="meta">
        Начни со стартового набора: {STARTER_HABITS.map((h) => h.name).join(', ')} — или добавь свои.
      </p>
      <div className="empty-actions">
        <button type="button" className="btn" onClick={add} disabled={busy}>
          {busy ? 'Добавляем…' : 'Добавить стартовый набор'}
        </button>
        {children}
      </div>
    </div>
  )
}
