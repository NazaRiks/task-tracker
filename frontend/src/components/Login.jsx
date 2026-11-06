import { useState } from 'react'
import { supabase } from '../lib/supabase.js'
import Logo from './Logo.jsx'

// Декоративная полоска квадратиков из макета: d — сделано, m — пропуск, пусто — нет отметки.
const ART = ['d', 'd', 'm', 'd', 'd', 'd', 'd', 'm', 'd', 'd', 'd', 'd', '', '']

function errorText(error) {
  const msg = (error?.message || '').toLowerCase()
  if (msg.includes('invalid login credentials')) return 'Неверный email или пароль.'
  if (msg.includes('email not confirmed')) return 'Email не подтверждён.'
  if (error?.status === 429) return 'Слишком много попыток. Подождите немного.'
  if (msg.includes('fetch') || msg.includes('network')) return 'Нет соединения с сервером.'
  return 'Не удалось войти. Попробуйте ещё раз.'
}

export default function Login() {
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')

  async function onSubmit(e) {
    e.preventDefault()
    if (busy) return
    setError('')
    setBusy(true)
    try {
      const { error } = await supabase.auth.signInWithPassword({ email: email.trim(), password })
      if (error) setError(errorText(error))
    } catch (err) {
      setError(errorText(err))
    } finally {
      setBusy(false)
    }
  }

  return (
    <section className="screen s-login">
      <form className="card login" onSubmit={onSubmit}>
        <Logo />
        <div className="login-art" aria-hidden="true">
          {ART.map((c, i) => <i key={i} className={c || undefined} />)}
        </div>
        <h1>Вход в трекер</h1>
        <label className="field">
          Email
          <input
            type="email"
            placeholder="you@example.com"
            autoComplete="email"
            required
            value={email}
            onChange={(e) => setEmail(e.target.value)}
          />
        </label>
        <label className="field">
          Пароль
          <input
            type="password"
            placeholder="••••••••"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {error && <p className="form-error" role="alert">{error}</p>}
        <button className="btn" type="submit" disabled={busy}>
          {busy ? 'Входим…' : 'Войти'}
        </button>
        <p className="note">Регистрация закрыта. Это личный трекер.</p>
      </form>
    </section>
  )
}
