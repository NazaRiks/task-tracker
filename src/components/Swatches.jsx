import { PALETTE, colorVar } from '../lib/habits.js'

export default function Swatches({ value, onChange }) {
  return (
    <div className="swatches" role="group" aria-label="Цвет">
      {PALETTE.map((c) => (
        <button
          key={c.id}
          type="button"
          className={c.id === value ? 'sw on' : 'sw'}
          style={{ '--sw': colorVar(c.id) }}
          title={c.label}
          aria-label={c.label}
          aria-pressed={c.id === value}
          onClick={() => c.id !== value && onChange(c.id)}
        />
      ))}
    </div>
  )
}
