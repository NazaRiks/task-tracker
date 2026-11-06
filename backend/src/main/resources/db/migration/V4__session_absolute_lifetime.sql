-- Абсолютный срок сессии: 90 дней от создания, даже при постоянном продлении. Ограничение в базе
-- страхует код: ошибка в продлении не сможет сделать сессию вечной.
-- Интервал в часах, а не в днях: прибавление дней зависит от часового пояса соединения.
UPDATE sessions
SET expires_at = created_at + interval '2160 hours'
WHERE expires_at > created_at + interval '2160 hours';

ALTER TABLE sessions
  ADD CONSTRAINT sessions_absolute_lifetime
  CHECK (expires_at <= created_at + interval '2160 hours');
