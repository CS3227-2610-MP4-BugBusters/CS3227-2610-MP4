-- report-discussion D1: additive shared migration for report discussion comments.
-- Apply after src/backend/shared/migrations/002_add_report_timestamps.sql,
-- on fresh databases only. The comment column is named `text` to match the
-- Maintainer's deployed V3__maintainer_evidence.sql table; the API field is `body`.
-- The deployed Flyway copy is
-- src/backend/auth/src/main/resources/db/shared/V5__report_comments.sql.
CREATE TABLE report_comments (
    id uuid PRIMARY KEY,
    report_id uuid NOT NULL REFERENCES reports(id),
    author_id uuid NOT NULL,
    author_role text NOT NULL CHECK (author_role IN ('REPORTER','DEVELOPER','MAINTAINER')),
    text text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT now()
);

-- Serves the oldest-first discussion thread for one report.
CREATE INDEX report_comments_report_created_idx
    ON report_comments (report_id, created_at, id);

-- Serves the per-author comment rate-limit window count.
CREATE INDEX report_comments_author_created_idx
    ON report_comments (author_id, created_at);
