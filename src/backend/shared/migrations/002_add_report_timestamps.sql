-- D7: additive shared migration. Apply after
-- src/backend/shared/migrations/001_create_reports.sql and before
-- src/backend/developer/migrations/create_investigation_checklists.sql,
-- on fresh databases only.
ALTER TABLE reports
    ADD COLUMN created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN updated_at timestamptz NOT NULL DEFAULT now();

-- Serves the newest-first Reporter list and the rate-limit window count.
CREATE INDEX reports_reporter_created_idx
    ON reports (reporter_id, created_at DESC, id);
