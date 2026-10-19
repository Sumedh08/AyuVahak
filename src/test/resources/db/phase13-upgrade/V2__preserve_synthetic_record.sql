ALTER TABLE synthetic_record ADD COLUMN migration_marker VARCHAR(32);
UPDATE synthetic_record SET migration_marker = 'V2_APPLIED';
