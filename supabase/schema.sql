-- Lock In Tracker — схема базы данных.
-- Выполнить целиком в Supabase → SQL Editor → New query → Run.
-- Скрипт можно запускать повторно: таблицы создаются только если их нет, политики пересоздаются.

-- ============================================================
-- Таблицы
-- ============================================================

create table if not exists public.habits (
  id          uuid primary key default gen_random_uuid(),
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  name        text not null check (char_length(btrim(name)) between 1 and 80),
  color       text not null default 'green'
              check (color in ('green', 'blue', 'violet', 'amber', 'teal', 'pink', 'orange', 'slate')),
  position    integer not null default 0,
  start_date  date not null default current_date,
  created_at  timestamptz not null default now(),
  -- нужен для составного внешнего ключа из checkins
  unique (id, user_id)
);

create table if not exists public.checkins (
  habit_id    uuid not null,
  user_id     uuid not null default auth.uid() references auth.users (id) on delete cascade,
  day         date not null,
  created_at  timestamptz not null default now(),
  primary key (habit_id, day),
  -- отметка может ссылаться только на задачу того же пользователя
  foreign key (habit_id, user_id) references public.habits (id, user_id) on delete cascade
);

create index if not exists habits_user_position_idx on public.habits (user_id, position);
create index if not exists checkins_user_day_idx on public.checkins (user_id, day);

-- ============================================================
-- Права: anon не получает ничего, authenticated — только нужное
-- ============================================================

revoke all on table public.habits   from anon;
revoke all on table public.checkins from anon;
revoke all on table public.habits   from authenticated;
revoke all on table public.checkins from authenticated;

grant select, insert, update, delete on table public.habits   to authenticated;
grant select, insert, delete         on table public.checkins to authenticated;

-- ============================================================
-- Row Level Security
-- ============================================================

alter table public.habits   enable row level security;
alter table public.checkins enable row level security;

-- habits: только свои строки
drop policy if exists "habits_select_own" on public.habits;
drop policy if exists "habits_insert_own" on public.habits;
drop policy if exists "habits_update_own" on public.habits;
drop policy if exists "habits_delete_own" on public.habits;

create policy "habits_select_own" on public.habits
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy "habits_insert_own" on public.habits
  for insert to authenticated
  with check (user_id = (select auth.uid()));

create policy "habits_update_own" on public.habits
  for update to authenticated
  using (user_id = (select auth.uid()))
  with check (user_id = (select auth.uid()));

create policy "habits_delete_own" on public.habits
  for delete to authenticated
  using (user_id = (select auth.uid()));

-- checkins: только свои строки; при вставке задача должна принадлежать тому же пользователю
drop policy if exists "checkins_select_own" on public.checkins;
drop policy if exists "checkins_insert_own" on public.checkins;
drop policy if exists "checkins_delete_own" on public.checkins;

create policy "checkins_select_own" on public.checkins
  for select to authenticated
  using (user_id = (select auth.uid()));

create policy "checkins_insert_own" on public.checkins
  for insert to authenticated
  with check (
    user_id = (select auth.uid())
    and exists (
      select 1 from public.habits h
      where h.id = habit_id
        and h.user_id = (select auth.uid())
    )
  );

create policy "checkins_delete_own" on public.checkins
  for delete to authenticated
  using (user_id = (select auth.uid()));
