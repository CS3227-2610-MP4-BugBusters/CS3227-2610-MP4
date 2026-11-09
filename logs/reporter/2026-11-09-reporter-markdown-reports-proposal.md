# Reporter Markdown reports proposal - 9 November 2026

Planning only. No application code, tests or migrations were changed.

## Context

Branch `workflow` at `d664503`. This followed two `openspec-explore` sessions on
the Reporter role (based on `overview.md`) and on GitHub-style Write/Preview
Markdown. The feature owner chose to have Developer and Maintainer views render
the Markdown as well (option B: a shared safe renderer), rather than a
Reporter-only preview.

Inputs read:
- `overview.md` sections 3, 4, 6, 7 and 8
- `openspec/specs/maintainer-report-browser/spec.md` (MNT-READ-03 and MNT-READ-05, QR-07)
- `shared-report-storage` SR-01
- `src/backend/shared/migrations/001_create_reports.sql`
- `workflow/developer/assigned-reports-api-contract.md`
- `src/backend/developer/README.md`
- `src/backend/auth/build.gradle`
- `src/frontend/maintainer/src/{domain.ts,App.tsx}` and `package.json`

## Created

`openspec/changes/reporter-markdown-reports/`:
- `proposal.md`
- `specs/reporter-reports/spec.md` (RPT-01..07, new)
- `specs/safe-markdown/spec.md` (MDR-01..07, new)
- `specs/maintainer-report-browser/spec.md` (MODIFIED MNT-READ-05)
- `design.md` (D1..D9)
- `tasks.md` (6 groups, 21 tasks)

## Findings recorded in the artifacts

- The implemented Maintainer spec forbids interpreted Markdown
  (MNT-READ-05, and its "UI state and concurrency" prose). The delta needs
  Maintainer owner approval (task 1.1).
- SR-01 has no Reporter detail fields or timestamps, but the Maintainer DTO
  already expects them. D7 adds them in an additive Reporter migration, which
  needs Developer owner sign-off.
- The Developer frontend has no report-detail screen. D9 adds a read-only
  source panel, which needs Developer owner approval.
- Status wire values differ between the Developer contract (`SUBMITTED`) and the
  Maintainer DTO labels (`Submitted`). Noted in D6 and not fixed here.
- Images are disabled in rendered Markdown so that injected content can't leak
  data through image requests.

## Checks

- `openspec validate reporter-markdown-reports --strict --no-interactive`: valid.
- `openspec status --change reporter-markdown-reports`: 4/4 artifacts complete.

## Agents and resources

Single main-session agent (Claude Opus 5.5) using the `openspec-explore` and
`openspec-propose` skills. No specialist subagents were dispatched, and no Red or
Green was claimed. Token and cache measurements were unavailable.

## Next action

Feature-owner review of the artifacts, then task 1.1 approvals. Groups 2 and 3
can then run in parallel worktrees through the TDD agent pipeline.
