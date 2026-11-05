import Logo from './Logo.jsx'

export default function NotConfigured() {
  return (
    <section className="screen s-login">
      <div className="card login setup">
        <Logo />
        <h1>Приложение не настроено</h1>
        <p className="meta">Не заданы адрес и ключ Supabase, поэтому подключиться к базе нельзя.</p>
        <ol>
          <li>
            Локально: скопируйте <code>.env.example</code> в <code>.env</code>, укажите{' '}
            <code>VITE_SUPABASE_URL</code> и <code>VITE_SUPABASE_KEY</code> и перезапустите{' '}
            <code>npm run dev</code>.
          </li>
          <li>
            На GitHub Pages: добавьте эти переменные в Settings → Secrets and variables → Actions →
            Variables и перезапустите деплой.
          </li>
        </ol>
        <p className="note">Подробности — в README.md.</p>
      </div>
    </section>
  )
}
