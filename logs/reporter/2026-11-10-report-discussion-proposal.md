# Report discussion: proposal (10 Nov 2026)

## Summary

The owner asked how to build a GitHub-style discussion between Reporter and
Developer. A new OpenSpec change, `report-discussion`, records the answer. It
contains planning artifacts only; no code was changed.
`openspec validate --strict` passes.

**Owner decisions (10 Nov):**
- Comments are allowed on closed reports.
- Comments do not change the report's version or `updated_at`.
- Comment bodies use the same Write | Preview editor and safe renderer as the
  description.

**Scope:**
- One shared thread per report, open to three participants:
  - the Reporter who filed the report;
  - the currently assigned Developer;
  - any Maintainer.
- Comments are append-only and must be under 20,000 characters.
- Each author can post at most 10 comments per minute.
- The timeline is built on the read side.
- Developer private notes and drafts are never shown in the thread.

**Prerequisite:** the Reporter backend is not in the deployment Dockerfile.
Task 1.4 wires it into the auth server, following the pattern of the Developer
handlers.

**Conflicts that need Maintainer owner approval (task 1.1):**
- The Maintainer workflow contract says "comments increment the aggregate
  version".
- MNT-DISC-01 specifies plain-text discussion.
- The `maintainer-report-browser` spec says comments render as escaped text.

The Maintainer spec delta waits until `reporter-markdown-reports` is archived,
because that change already modifies MNT-READ-05 (task 1.2).

## Details

Artifacts are in `openspec/changes/report-discussion/`:
- `proposal.md`
- `specs/report-discussion/spec.md` (DSC-01..06)
- `design.md` (D1–D8)
- `tasks.md` (5 groups)

## Apply started (10 Nov)

The owner ran `/openspec-apply-change report-discussion`. Progress is 0 of 13.

**Blocked tasks:**
- 1.1 needs the Maintainer owner's approval, which is external.
- 1.2 waits until `reporter-markdown-reports` is archived.
- Group 2 needs both 1.1 and 1.3.

**Task 1.3:** the contract draft `workflow/reporter/report-discussion-api-contract.md` is ready. It awaits the feature owner's approval.

**Developer access:** a "cannot see" request gets 403 `FORBIDDEN` with "Access denied". This follows the existing `ChecklistDraftHttpHandler`, which gives the same response for a missing report and for one that is not assigned to the Developer.

**Page size:** the default page size is 20, the same as RPT-04, as DSC-04 requires.
