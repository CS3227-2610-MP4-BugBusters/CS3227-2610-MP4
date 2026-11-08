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
