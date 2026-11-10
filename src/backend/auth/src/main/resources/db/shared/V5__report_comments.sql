-- report-discussion D1 (deployed Flyway copy of
-- src/backend/shared/migrations/003_report_comments.sql).
--
-- The deployed database already has report_comments(id, report_id, author_id,
-- text, created_at), created by the Maintainer's V3__maintainer_evidence.sql.
-- This migration upgrades that table in place to the D1 shape; it does not
-- create the table. Before this change only the Maintainer repository wrote
-- comments, so existing rows are backfilled with author_role 'MAINTAINER'.
-- Every statement is idempotent: running it a second time changes nothing.
ALTER TABLE report_comments ADD COLUMN IF NOT EXISTS author_role text;

UPDATE report_comments SET author_role = 'MAINTAINER' WHERE author_role IS NULL;

ALTER TABLE report_comments ALTER COLUMN author_role SET NOT NULL;

-- Same constraint name PostgreSQL gives the inline CHECK in 003.
DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1 FROM pg_constraint
        WHERE conrelid = 'public.report_comments'::regclass
          AND conname = 'report_comments_author_role_check'
    ) THEN
        ALTER TABLE report_comments
            ADD CONSTRAINT report_comments_author_role_check
            CHECK (author_role IN ('REPORTER','DEVELOPER','MAINTAINER'));
    END IF;
END
$$;

-- Serves the oldest-first discussion thread for one report.
CREATE INDEX IF NOT EXISTS report_comments_report_created_idx
    ON report_comments (report_id, created_at, id);

-- Serves the per-author comment rate-limit window count.
CREATE INDEX IF NOT EXISTS report_comments_author_created_idx
    ON report_comments (author_id, created_at);
