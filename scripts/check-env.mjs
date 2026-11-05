// Проверка переменных окружения перед сборкой в CI (см. .github/workflows/deploy.yml).
// Падает с понятной ошибкой, если переменные не заданы или вместо publishable-ключа указан секретный.

const url = (process.env.VITE_SUPABASE_URL || '').trim()
const key = (process.env.VITE_SUPABASE_KEY || '').trim()
const errors = []

if (!url) {
  errors.push('Не задана переменная VITE_SUPABASE_URL (GitHub → Settings → Secrets and variables → Actions → Variables).')
} else {
  try {
    const u = new URL(url)
    if (u.protocol !== 'https:') errors.push('VITE_SUPABASE_URL должен начинаться с https://')
  } catch {
    errors.push(`VITE_SUPABASE_URL не похож на адрес: «${url}»`)
  }
}

if (!key) {
  errors.push('Не задана переменная VITE_SUPABASE_KEY (GitHub → Settings → Secrets and variables → Actions → Variables).')
} else if (key.startsWith('sb_secret_')) {
  errors.push('VITE_SUPABASE_KEY — секретный ключ (sb_secret_…). Нужен publishable-ключ (sb_publishable_…) или legacy anon.')
} else if (key.split('.').length === 3) {
  // legacy JWT-ключ: проверяем роль, service_role в браузер попадать не должен
  try {
    const payload = JSON.parse(Buffer.from(key.split('.')[1], 'base64url').toString('utf8'))
    if (payload.role && payload.role !== 'anon') {
      errors.push(`VITE_SUPABASE_KEY имеет роль «${payload.role}». Нужен anon / publishable-ключ, service_role использовать нельзя.`)
    }
  } catch {
    // не JWT — пропускаем
  }
}

if (errors.length) {
  for (const e of errors) console.error(`::error::${e}`)
  console.error('\nСборка остановлена: без правильных переменных сайт был бы нерабочим.')
  process.exit(1)
}
console.log('Переменные окружения в порядке.')
