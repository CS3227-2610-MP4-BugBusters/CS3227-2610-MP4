# Design

## Context

Maintainer detail already projects `report_shared_notes` as shared evidence; existing Developer private notes are independently revisioned and inaccessible to Maintainers. See proposal and DSN requirements. Checklist Share establishes report-first locking and proposal invalidation.

## Goals / Non-Goals

Provide one explicitly published Developer note with a separate saved draft and preserve existing private notes and unrelated shared evidence. Reporter access, automatic copying of private notes, AI use, attachments and a general note-history feature are excluded.

## Decisions

1. **Independent storage:** Add a per-report shared-note draft table with text, revision and nullable ID of its published `report_shared_notes` row. Initialize the published row only on first nonblank Share and update only that row on later changed Shares. Existing shared evidence remains untouched. This is preferable to exposing the private note or replacing all shared-note rows. No automatically inferred legacy note ownership.
2. **Version and locking:** Recheck active account/session generation, then lock report followed by draft/published rows. Save checks and increments draft revision only. Share checks draft revision and report version, requires a saved nonblank draft, compares the existing published text, and commits publication, report version, history and proposal invalidation atomically. Follow the current checklist race semantics and safe integer limits.
3. **HTTP boundary:** Use separate Developer-only `GET`/`PUT /api/developer/reports/{id}/shared-notes/draft` and `POST /api/developer/reports/{id}/shared-notes/share`; draft read returns `{text,draftRevision,reportVersion}`, PUT accepts `{text,expectedDraftRevision}`, Share accepts `{expectedDraftRevision,expectedReportVersion}` and returns `{reportVersion,changed}`. Resolve actor from session; existing CSRF, no-store and safe error conventions apply. Maintainer reads the existing shared evidence projection; no Reporter route is introduced.
4. **UI:** A separate shared-note editor, separate Save and Share, fixed safe errors, stale/unsaved preservation and clearing on report/session loss. Publication uses the saved server draft, not the editor contents. Existing Maintainer display renders shared note as escaped text.
5. **Evaluation:** Use the full TDD/review path because authorization, shared persistence and lifecycle are involved. Record actual coordination and accessible token/time measurements, plus unavailable metrics; no efficiency claim from a single run.

## Risks / Trade-offs

- [Older manual schemas] → additive Flyway migration, fresh install and data-preserving upgrade checks; never repair histories or delete evidence automatically.
- [Accidental private disclosure] → separate fields and routes, explicit publication, negative authenticated projection checks and independent security review.
- [Share races with close/reassignment] → shared account/report lock order, conditional updates and PostgreSQL race tests.

## Migration Plan

Add a new shared Flyway migration after the current highest shared version. Preserve all pre-existing notes and reports. Rollback application code without destructive database reversal after compatibility review.
