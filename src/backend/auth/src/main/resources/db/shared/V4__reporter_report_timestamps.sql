-- report-discussion D7: registers the Reporter timestamp migration
-- (src/backend/shared/migrations/002_add_report_timestamps.sql) with the deployed
-- shared Flyway history. Idempotent next to V3__maintainer_evidence.sql, which
-- already adds created_at and updated_at.
ALTER TABLE reports
    ADD COLUMN IF NOT EXISTS created_at timestamptz NOT NULL DEFAULT now(),
    ADD COLUMN IF NOT EXISTS updated_at timestamptz NOT NULL DEFAULT now();

-- Serves the newest-first Reporter list and the rate-limit window count.
CREATE INDEX IF NOT EXISTS reports_reporter_created_idx
    ON reports (reporter_id, created_at DESC, id);
