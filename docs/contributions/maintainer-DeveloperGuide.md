# Maintainer contribution to DeveloperGuide

Implemented the Maintainer role against the shared Java/PostgreSQL application.
Use the repository build and migration sources; MP2/MP3 supplied documentation
references rather than copied application code.

## Components and contracts

Use `src/frontend/maintainer` for React controls and typed same-origin HTTP adapters.
Use `src/backend/maintainer` for workflow rules, safe DTOs, JDBC repositories and
bounded queue-summary processing. Spring controllers and shared identity integration
live under `src/backend/platform`. The Maintainer backend README describes the
summary gateway and durable admission ports the shared AI owner must supply.

Preserve the Developer checklist constructor and Save/Share contracts. The main
application adds an in-transaction publication observer for history. Account
eligibility locks precede report locks; version checks, state changes and history
commit together. The shared auth adapter also checks Developer eligibility inside
write transactions and maps account revocation to 401, including failures consumed
by existing handlers. Private checklist saves do not change report versions.

Authentication uses fixed roles, hashed passwords, server-side sessions and CSRF.
Use the authentication README for provisioning and local setup. Keep credentials
out of source, command arguments and logs. Shared report migrations and auth
migrations use separate histories; package canonical SQL sources rather than
maintaining duplicate migration files.

## Verification and delivery

Run `python src/backend/auth/test/run_setup.py` for the disposable PostgreSQL backend
checks. Select a class with `--tests` for focused development. Run `npm run verify`
inside each changed frontend. CI retains complete checks and builds the packaged
application. Follow the repository TDD workflow and preserve independently verified
baseline tests. Record unavailable independent reviews as pending.

Use `src/backend/platform/deployment/README.md` for Render configuration, deployment
gates and rollback. Development and production have separate configuration and
main-only GitHub environments. Production requires Developer-owner approval and
prevents self-review. Neither Render provisioning nor live deployment has been
performed here; deploy hooks and host connectivity remain unverified. The Pages
site is a separate team deliverable, not the application host. The repository uses
`main`; reconcile any assignment reference to `master` with the final submission.
