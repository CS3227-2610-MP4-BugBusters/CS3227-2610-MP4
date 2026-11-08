# Design: shared report storage

## Minimum record

Create one `reports` table: `id uuid` primary key; `title text NOT NULL`; `description text NOT NULL`; `reporter_id uuid NOT NULL`; `assignee_id uuid NULL`; `status text NOT NULL` default `SUBMITTED` and constrained to `SUBMITTED`, `ASSIGNED`, `IN_PROGRESS`, `RESOLUTION_PROPOSED`, `CLOSED`; `version bigint NOT NULL` default 0 with nonnegative check; `current_proposal_id uuid NULL`. Application generates UUIDs. Do not add an assumed priority default or ownership/lifecycle restrictions that have not been agreed.

UUID identity and proposal columns are references in the application contract, but are not database foreign keys in this increment because the canonical accounts and proposals tables do not yet exist. Auth services must validate actor identity, role, active state and ownership; a future migration must reconcile existing rows before adding foreign keys. The database schema alone does not authorize requests, validate active Developers, enforce status transitions, or guarantee proposal integrity.

## Version and transactions

Mutable report operations must use `UPDATE reports ... WHERE id = ? AND version = ?`, atomically incrementing `version`, and treat no updated row as a conflict or missing record according to the API's authorization and disclosure rules. Do not auto-increment versions via a trigger: private checklist Save must not alter the report version. Share, reassignment and closure require the existing report-first locking contract. The schema cannot force callers to use version checks; integration tests must exercise real services when available.

## Migration lifecycle

A numbered SQL file applies once to a fresh disposable PostgreSQL database before `create_investigation_checklists.sql`. No `CREATE TABLE IF NOT EXISTS` or silent upgrade of an existing table: a runner must track applied versions and fail on an incompatible existing schema. No migration framework currently exists; document manual `psql -v ON_ERROR_STOP=1` use, and add migration tracking when the shared application's framework is selected. Never run test fixtures against a shared or production database.

## Verification

Run migration on a freshly created disposable PostgreSQL instance, apply the existing checklist migration, and test constraints, reporter/assignee values, valid statuses, checklist persistence and report-version compare-and-swap including concurrent writers. Synthetic UUIDs do not prove identity or proposal foreign-key integrity. The existing Developer HTTP/store tests need separate integration against this schema and real authentication before feature completion.
