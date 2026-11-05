import { noMouseFocus } from '../lib/noMouseFocus.js'
import Logo from './Logo.jsx'

export const TABS = [
  { id: 'today', label: 'Сегодня' },
  { id: 'year', label: 'Год' },
  { id: 'stats', label: 'Статистика' },
  { id: 'habits', label: 'Задачи' },
]

function shortEmail(email = '') {
  const [name] = email.split('@')
  return name ? `${name}@…` : ''
}

export default function Header({ tab, onTab, email, onSignOut, signingOut }) {
  return (
    <header className="app-header">
      <Logo />
      <nav className="nav" aria-label="Разделы">
        {TABS.map((t) => (
          <button
            key={t.id}
            type="button"
            className={tab === t.id ? 'on' : undefined}
            aria-current={tab === t.id ? 'page' : undefined}
            onMouseDown={noMouseFocus}
            onClick={() => onTab(t.id)}
          >
            {t.label}
          </button>
        ))}
      </nav>
      <div className="user">
        <span title={email}>{shortEmail(email)}</span>
        <button type="button" onClick={onSignOut} disabled={signingOut}>Выйти</button>
      </div>
    </header>
  )
}
