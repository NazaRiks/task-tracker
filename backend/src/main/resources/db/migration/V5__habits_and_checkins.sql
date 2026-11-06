CREATE TABLE habits (
  id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
  user_id    uuid        NOT NULL REFERENCES users (id) ON DELETE CASCADE,
  name       text        NOT NULL CHECK (char_length(name) BETWEEN 1 AND 80),
  color      text        NOT NULL CHECK (
    color IN ('green', 'blue', 'violet', 'amber', 'teal', 'pink', 'orange', 'slate')
  ),
  position   integer     NOT NULL,
  start_date date        NOT NULL CHECK (start_date >= DATE '2000-01-01'),
  created_at timestamptz NOT NULL DEFAULT now(),
  UNIQUE (id, user_id) -- цель составного внешнего ключа из checkins
);

CREATE INDEX habits_user_position_idx ON habits (user_id, position); -- список задач пользователя

-- Составной внешний ключ гарантирует на уровне базы: user_id отметки всегда равен владельцу задачи.
-- Поэтому запросы к отметкам могут фильтровать по user_id без соединения с habits.
CREATE TABLE checkins (
  habit_id uuid NOT NULL,
  user_id  uuid NOT NULL,
  day      date NOT NULL CHECK (day >= DATE '2000-01-01'),
  PRIMARY KEY (habit_id, day),
  FOREIGN KEY (habit_id, user_id) REFERENCES habits (id, user_id) ON DELETE CASCADE
);

CREATE INDEX checkins_user_day_idx ON checkins (user_id, day); -- отметки за период и расчёт серий
