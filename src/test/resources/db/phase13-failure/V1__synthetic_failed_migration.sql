CREATE TABLE synthetic_failure_probe (
    probe_id INTEGER PRIMARY KEY
);
SELECT 1 / 0;
