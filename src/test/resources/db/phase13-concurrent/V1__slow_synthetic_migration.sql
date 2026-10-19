CREATE TABLE synthetic_migration_lock_probe (
    probe_id INTEGER PRIMARY KEY
);
SELECT pg_sleep(3);
INSERT INTO synthetic_migration_lock_probe (probe_id) VALUES (1);
