# lock in — трекер привычек

Личный трекер привычек: каждый день отмечаешь, выполнил задачу или нет, и видишь годовую сетку квадратиков по каждой задаче, как contributions на GitHub.

**Сайт:** https://nazariks.github.io/task-tracker/

- **Сегодня:** большие галочки, текущая серия и рекорд, последние 7 дней (можно исправлять задним числом), кольцо «N из M сделано».
- **Год:** сетка 53 недели × 7 дней на каждую задачу; сделано — цвет задачи, пропуск — красный.
- **Статистика:** неделя / месяц / год: процент выполнения, отметки, «идеальные дни», диаграмма, таблица по задачам.
- **Задачи:** добавить, переименовать, выбрать цвет, задать дату начала, поменять порядок, удалить.

Стек: React + Vite, Supabase (Auth + Postgres с Row Level Security), хостинг на GitHub Pages. Регистрации нет: единственного пользователя создаёт владелец.

---

## 1. Создать проект Supabase

1. Зайди на https://supabase.com, войди и нажми **New project**.
2. Укажи имя, пароль базы (сохрани его где-нибудь) и регион поближе. Дождись, пока проект создастся.

## 2. Создать таблицы и политики доступа

1. В проекте открой **SQL Editor** → **New query**.
2. Скопируй туда всё содержимое файла [`supabase/schema.sql`](supabase/schema.sql) и нажми **Run**.

Скрипт создаёт таблицы `habits` и `checkins`, включает Row Level Security (каждый пользователь видит и меняет только свои строки) и отзывает права у анонимной роли `anon`. Запускать его повторно безопасно.

Проверка: в **Table Editor** появились таблицы `habits` и `checkins`, у обеих есть пометка RLS enabled.

## 3. Закрыть регистрацию и анонимный вход

**Authentication** → **Sign In / Providers** (в старых версиях панели — **Providers** и **Settings**):

1. Выключи **Allow new users to sign up**. После этого никто не сможет создать аккаунт сам.
2. Выключи **Allow anonymous sign-ins**, если этот переключатель включён.
3. Провайдер **Email** оставь включённым: через него входит созданный вручную пользователь.

Сохрани изменения.

## 4. Создать пользователя

**Authentication** → **Users** → **Add user** → **Create new user**:

1. Введи email и надёжный пароль.
2. Отметь **Auto Confirm User**, чтобы не нужно было подтверждать почту.
3. Нажми **Create user**.

С этими email и паролем ты и будешь входить в трекер.

## 5. Указать адрес сайта (Site URL)

**Authentication** → **URL Configuration**:

- **Site URL:** `https://nazariks.github.io/task-tracker/`
- В **Redirect URLs** можно добавить `http://localhost:5173` для локальной разработки.

## 6. Взять URL проекта и publishable-ключ

**Project Settings** → **API Keys** (или кнопка **Connect** на главной странице проекта):

- **Project URL:** вида `https://abcdefgh.supabase.co`. Это `VITE_SUPABASE_URL`.
- **Publishable key** (`sb_publishable_…`) или, во вкладке legacy, ключ **anon public**. Это `VITE_SUPABASE_KEY`.

> ⚠️ Используй только publishable / anon ключ. Он попадает в браузер, и это нормально: данные защищены RLS-политиками.
> **Secret / service_role ключ никуда не вставляй.** Он обходит RLS и даёт полный доступ к базе. Сборка на GitHub сама откажется собираться, если вставить такой ключ.

## 7. Добавить переменные в GitHub

В репозитории: **Settings** → **Secrets and variables** → **Actions** → вкладка **Variables** → **New repository variable**. Создай две переменные:

| Имя | Значение |
|---|---|
| `VITE_SUPABASE_URL` | Project URL из шага 6 |
| `VITE_SUPABASE_KEY` | publishable / anon ключ из шага 6 |

Это именно **Variables**, а не Secrets: workflow читает их как `vars.*`. Если переменная пустая или в ней секретный ключ, сборка упадёт с понятной ошибкой, и нерабочий сайт не будет опубликован.

## 8. Включить GitHub Pages

1. **Settings** → **Pages** → **Build and deployment** → **Source:** выбери **GitHub Actions**.
2. Запусти деплой: сделай push в `main` или открой **Actions** → **Deploy to GitHub Pages** → **Run workflow**.
3. Через минуту-две сайт откроется по адресу https://nazariks.github.io/task-tracker/.

Если ты добавил переменные уже после неудачного запуска, просто перезапусти workflow (**Re-run jobs**).

## 9. Запустить локально

Нужен Node.js 20 или новее.

```bash
git clone https://github.com/NazaRiks/task-tracker.git
cd task-tracker
npm install
cp .env.example .env     # в Windows PowerShell: Copy-Item .env.example .env
```

Заполни `.env` значениями из шага 6 и запусти:

```bash
npm run dev
```

Открой адрес, который покажет Vite (обычно http://localhost:5173).

Файл `.env` в git не попадает: он указан в `.gitignore`. Если переменные не заданы, приложение покажет экран «Приложение не настроено».

Другие команды:

- `npm run build`: сборка в `dist/`.
- `npm run preview`: просмотр собранной версии.
- `npm run check`: проверка правил расчёта статистики на граничных случаях.

---

## Как устроено

- `supabase/schema.sql`: таблицы, индексы, RLS-политики, права.
- `src/lib/`:
  - `supabase.js`: клиент;
  - `dates.js`: локальные даты `YYYY-MM-DD` без сдвига из-за UTC;
  - `useTrackerData.js`: загрузка данных постранично и оптимистичные отметки с очередью запросов на каждую ячейку;
  - `stats.js`: расчёты статистики.
- `src/screens/`: экраны «Сегодня», «Год», «Статистика», «Задачи».
- `design/design-mockup.html`: утверждённый макет, по которому сделан интерфейс.
- `.github/workflows/deploy.yml`: сборка и публикация на GitHub Pages.

Правила расчётов (пропуски, «возможные» дни, идеальные дни) описаны в `CLAUDE.md`.

> На бесплатном тарифе Supabase ставит проект на паузу после недели без активности. Если сайт перестал загружать данные, зайди в панель Supabase и нажми **Restore project**.
