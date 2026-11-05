import { useCallback, useEffect, useMemo, useState } from 'react'
import { isConfigured, supabase } from './lib/supabase.js'
import { habitInfo } from './lib/habits.js'
import { useToday } from './lib/useToday.js'
import { useTrackerData } from './lib/useTrackerData.js'
import Header from './components/Header.jsx'
import Login from './components/Login.jsx'
import NotConfigured from './components/NotConfigured.jsx'
import Toast from './components/Toast.jsx'
import Today from './screens/Today.jsx'
import Habits from './screens/Habits.jsx'

const EMPTY = new Set()

export default function App() {
  return <div className="wrap">{isConfigured ? <AuthGate /> : <NotConfigured />}</div>
}

function AuthGate() {
  // undefined — сессия ещё проверяется, null — не вошли
  const [session, setSession] = useState(undefined)

  useEffect(() => {
    let alive = true
    supabase.auth
      .getSession()
      .then(({ data }) => alive && setSession(data.session ?? null))
      .catch(() => alive && setSession(null))
    const { data: sub } = supabase.auth.onAuthStateChange((_event, s) => setSession(s ?? null))
    return () => {
      alive = false
      sub.subscription.unsubscribe()
    }
  }, [])

  if (session === undefined) return <p className="loading">Загрузка…</p>
  if (!session) return <Login />
  return <Main key={session.user.id} session={session} />
}

function Main({ session }) {
  const [tab, setTab] = useState('today')
  const [signingOut, setSigningOut] = useState(false)
  const [notice, setNotice] = useState('')
  const clearNotice = useCallback(() => setNotice(''), [])

  const today = useToday()
  const data = useTrackerData(setNotice)
  const { habits, checkins, toggle: toggleCheckin } = data

  const infos = useMemo(
    () => habits.map((h) => habitInfo(h, checkins.get(h.id) ?? EMPTY, today)),
    [habits, checkins, today],
  )

  async function signOut() {
    setSigningOut(true)
    try {
      const { error } = await supabase.auth.signOut()
      // Если сервер недоступен — всё равно очищаем локальную сессию.
      if (error) await supabase.auth.signOut({ scope: 'local' })
    } finally {
      setSigningOut(false)
    }
  }

  // Будущие дни отмечать нельзя.
  const toggle = useCallback((habitId, day) => {
    if (day <= today) toggleCheckin(habitId, day)
  }, [toggleCheckin, today])
  const addStarter = () => data.addStarter(today)
  const addHabit = (name, color) => data.addHabit(name, color, today)

  let content
  if (data.status === 'loading') {
    content = <p className="loading">Загрузка…</p>
  } else if (data.status === 'error') {
    content = (
      <div className="card empty">
        <h2>Не удалось загрузить данные</h2>
        <p className="meta">Проверь подключение к интернету и попробуй ещё раз.</p>
        <div className="empty-actions">
          <button type="button" className="btn" onClick={data.reload}>Повторить</button>
        </div>
      </div>
    )
  } else if (tab === 'today') {
    content = (
      <Today
        today={today}
        infos={infos}
        onToggle={toggle}
        onStarter={addStarter}
        onGoHabits={() => setTab('habits')}
      />
    )
  } else if (tab === 'habits') {
    content = (
      <Habits
        habits={habits}
        today={today}
        onAdd={addHabit}
        onStarter={addStarter}
        onRename={data.renameHabit}
        onColor={data.setHabitColor}
        onStart={data.setHabitStart}
        onMove={data.moveHabit}
        onDelete={data.deleteHabit}
      />
    )
  } else {
    content = (
      <section className="screen">
        <p className="meta">Раздел появится на следующих этапах.</p>
      </section>
    )
  }

  return (
    <>
      <Header
        tab={tab}
        onTab={setTab}
        email={session.user.email}
        onSignOut={signOut}
        signingOut={signingOut}
      />
      {content}
      <Toast message={notice} onClose={clearNotice} />
    </>
  )
}
