#!/bin/bash
# Роли с наименьшими привилегиями. Выполняется официальным образом Postgres один раз, при
# инициализации пустого тома. Этот же скрипт запускают тесты, поэтому они проверяют настоящие права.
set -euo pipefail

: "${DB_MIGRATION_PASSWORD:?Не задан DB_MIGRATION_PASSWORD}"
: "${DB_PASSWORD:?Не задан DB_PASSWORD}"

# Пароли читаются через \getenv, а не передаются через -v: так они не видны в списке процессов.
psql -v ON_ERROR_STOP=1 -v db="$POSTGRES_DB" --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv owner_password DB_MIGRATION_PASSWORD
\getenv app_password DB_PASSWORD

-- Владелец схемы: под ним работает только Flyway при старте приложения.
CREATE ROLE tracker_owner LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
  PASSWORD :'owner_password';

-- Роль пула соединений: только работа с данными.
CREATE ROLE tracker_app LOGIN NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION
  PASSWORD :'app_password';

-- С Postgres 15 схемой public владеет владелец базы, поэтому tracker_owner может создавать в ней
-- объекты, а также доверенное расширение citext.
ALTER DATABASE :"db" OWNER TO tracker_owner;

-- По умолчанию PUBLIC может подключаться и создавать временные таблицы: убираем и то, и другое.
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO tracker_app;

REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO tracker_app;

-- Права на будущие объекты, которые создаст tracker_owner. TRUNCATE намеренно не выдаётся.
ALTER DEFAULT PRIVILEGES FOR ROLE tracker_owner IN SCHEMA public
  GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO tracker_app;
ALTER DEFAULT PRIVILEGES FOR ROLE tracker_owner IN SCHEMA public
  GRANT USAGE, SELECT ON SEQUENCES TO tracker_app;
SQL
