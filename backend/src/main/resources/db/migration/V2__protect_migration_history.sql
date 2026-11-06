-- Flyway создаёт свою таблицу от имени tracker_owner, поэтому default privileges выдали tracker_app
-- права и на неё. Роли приложения незачем читать и менять историю миграций.
REVOKE ALL ON TABLE flyway_schema_history FROM tracker_app;
