# Shared report storage

The reports migration creates the shared report record used by the role
backends. Apply it once to a fresh, disposable PostgreSQL database before the
Developer checklist migration:

```sh
psql "$MP4_TEST_DATABASE_URI" -v ON_ERROR_STOP=1 \
  -f src/backend/shared/migrations/001_create_reports.sql \
  -f src/backend/developer/migrations/create_investigation_checklists.sql
```

The schema stores UUID references in `reporter_id`, `assignee_id`, and
`current_proposal_id`. They are not foreign keys because the canonical account
and proposal tables are not yet available. The migration does not provide
authentication, role or ownership authorization, proposal integrity, status
transition enforcement, or automatic report-version checks. Mutating services
must compare the expected version in their update and increment it atomically.

This migration is for fresh databases only. It does not upgrade an existing
schema or track applied migration versions; do not rerun it against a database
that already has `reports`.

## 002 report timestamps

`migrations/002_add_report_timestamps.sql` (Reporter design D7) adds
`created_at` and `updated_at` (`timestamptz NOT NULL DEFAULT now()`) to
`reports` and an index on `(reporter_id, created_at DESC, id)`. Apply it after
`001_create_reports.sql` and before the Developer checklist migration, on fresh
databases only. Only the Reporter runner
(`src/backend/reporter/test/run-reporter-db.sh`) applies it; the shared and
Developer runners apply `001` only, so their schemas do not include these
columns.

## 003 report comments

`migrations/003_report_comments.sql` (report-discussion D1) adds the
`report_comments` table (`id, report_id, author_id, author_role, text,
created_at`) with indexes on `(report_id, created_at, id)` and
`(author_id, created_at)`. The comment column is `text`, as in the Maintainer's
table; the API field is `body`. Apply it after `002`, on fresh databases only.
Only the Reporter runner applies it.

The deployed database already has `report_comments` from the Maintainer's
`V3__maintainer_evidence.sql`, without `author_role`. The deployed app applies
`src/backend/auth/src/main/resources/db/shared/V5__report_comments.sql`, which
does not create the table: it adds `author_role`, backfills existing rows with
`'MAINTAINER'`, sets it `NOT NULL`, adds the role check and both indexes. It is
idempotent, and the resulting shape is the same as `003`'s.
