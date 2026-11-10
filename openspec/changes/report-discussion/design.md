# Design

## Context

- **Deployed app.** The deployed app is one Spring server built from
  `src/backend/auth`.
  - `auth/build.gradle` compiles `../developer/src` as an extra source directory.
  - `mp4.platform.AuthController` wraps the Developer's plain `HttpExchange`-style
    handlers, `AssignedReportListHttpHandler` and `ChecklistDraftHttpHandler`.
    It mounts them under `/api/developer/...` with the trusted session actor.
  - `src/backend/platform/deployment/Dockerfile` copies `developer/src` and
    `developer/migrations`, but nothing from `src/backend/reporter/`. So the
    Reporter API (RPT-01..10) runs only in its test harness today.
- **Reporter backend.** It is a plain JDK handler with
  `handle(HttpExchange, UUID actorId, String role, boolean active)`. Its store
  and its error envelope follow the Developer pattern. Its 2.3–2.11 tests run
  against a disposable PostgreSQL database.
- **What the Maintainer side expects.**
  - `workflow/maintainer/storage-adapter-contract.md` expects one shared
    discussion store per report.
  - `workflow/maintainer/workflow-service-contract.md` says comments increment
    the aggregate version.
  - `complete-maintainer-role` MNT-DISC-01 specifies plain-text discussion.

  The owner's 10 Nov decisions differ on the last two points (D3, D4).
- **Rendering.** `safe-markdown` (`src/frontend/shared/markdown`) provides
  `MarkdownView` and `MarkdownEditor`. Every role already uses them for the
  description.

## Goals / Non-Goals

**Goals:**
- One comment store and one service with three thin role endpoints.
- Authorization done per role on the server.
- Comments never change the report.
- The same safe Markdown rendering in every role.
- The Reporter backend running in the deployed app.

**Non-Goals:**
- Editing or deleting comments.
- Notifications, mentions, reactions, attachments, threads.
- AI features.
- A Reporter frontend application beyond the shared discussion component.

## Decisions

### D1 Shared table, Reporter-independent ownership
`src/backend/shared/migrations/003_report_comments.sql` adds this table:

```
report_comments (
  id uuid PRIMARY KEY,
  report_id uuid NOT NULL REFERENCES reports(id),
  author_id uuid NOT NULL,
  author_role text NOT NULL CHECK (author_role IN ('REPORTER','DEVELOPER','MAINTAINER')),
  body text NOT NULL,
  created_at timestamptz NOT NULL DEFAULT now()
)
```

It also adds the index `(report_id, created_at, id)` for the thread, and the index
`(author_id, created_at)` for the rate limit.

- The table lives in `shared/`, because every role reads it. This is the same
  reasoning as D7 in `reporter-markdown-reports`.
- `author_role` is stored at post time, so the thread can say "Developer" even
  after the author's role changes.
- Display names are joined from the auth accounts table when the thread is read,
  never stored.
- Reports are never deleted, so the foreign key is safe.

### D2 One service, three role endpoints
A `ReportDiscussionService` in a new `src/backend/discussion/src/` package takes a
trusted actor and decides whether that actor is a participant:

| Role | Participant when |
|---|---|
| Reporter | `reports.reporter_id = actor` |
| Developer | `reports.assignee_id = actor` |
| Maintainer | always |

- The actor must also be active.
- For a non-participant, each role's endpoint returns that role's existing
  "cannot see" response. For a Reporter that is the RPT-05 404. For a Developer
  it is the response defined by `developer-assigned-reports`.
- Endpoints:
  - `GET` and `POST` on `/api/reporter/reports/{id}/comments`
  - the same pair under `/api/developer/...`
  - the same pair under `/api/maintainer/...`
- Other methods return 405.
- The error envelope, `no-store`, the strict JSON reader with its 128 KiB cap,
  and the text rules reuse the Reporter code. `ReporterJson` and `validateText`
  move into a small shared helper rather than being copied.

### D3 Comments are not part of the report version (owner decision 10 Nov)
Posting inserts one `report_comments` row and appends nothing else.

- It doesn't touch `reports.version`, `updated_at`, the status or the proposal.
- It doesn't append a history event either. A comment *is* its own timeline
  entry.

**Why:**
- If comments bumped the version, every comment would make a pending Reporter
  edit fail with `VERSION_CONFLICT`.
- It would also let anyone push a report up the Maintainer queue. The 2.10
  security review already flagged that effect for no-op edits.

**Conflict:** the Maintainer workflow contract says "Comments increment the
aggregate version". That contract needs the Maintainer owner to accept this
decision (task 1.1).

### D4 Markdown bodies through safe-markdown (owner decision 10 Nov)
Comment bodies get the same treatment as the description:
- The server stores the source exactly as typed and never renders it.
- Each frontend renders it with `MarkdownView` and writes it with
  `MarkdownEditor`.
- The 2.3 text rules apply, with a 20,000-code-unit cap.

**Conflicts:**
- MNT-DISC-01 ("plain-text discussion").
- The `maintainer-report-browser` prose rule that every comment renders as
  escaped text.

Both need Maintainer owner approval (task 1.1). The spec delta is applied when
`reporter-markdown-reports` archives, because that change already modifies
MNT-READ-05 (task 1.2).

### D5 Posting rate limit counted from stored rows
This mirrors D10 of `reporter-markdown-reports`:
1. Take `pg_advisory_xact_lock` on the author.
2. Count the author's comments in the last 60 s.
3. If the count is 10 or more, return 429 with `Retry-After`. Otherwise insert.

Comments are never deleted, so the stored rows stay a complete record. The limit
is per author across all reports, so moving to another report doesn't evade it.

### D6 Timeline is a read-side merge
- The thread endpoint returns comments only.
- Each role's view fetches comments and its existing history projection, then
  merges the two by time on the client. Entries are ordered by time, then type,
  then id.
- No new history rows are written, which keeps D3 intact.
- The Reporter has no history projection today, so the Reporter view shows
  comments plus the report's own created and closed times.

### D7 Deploy the Reporter backend
- `auth/build.gradle` adds `srcDir '../reporter/src'` and
  `srcDir '../discussion/src'`.
- The Dockerfile copies `src/backend/reporter/src/` and
  `src/backend/discussion/src/`.
- `AuthController` mounts the Reporter handler at `/api/reporter/reports/**`
  using the trusted session actor, exactly as it wraps the Developer handlers.
- The synthetic harness stays for tests only.
- Real-session verification follows `add-shared-authentication`.

### D8 Shared frontend component
`src/frontend/shared/markdown` gains `ReportDiscussion`, which takes a fetch
adapter, the comments and the history entries. It renders:
- the timeline, with names and times as escaped text and bodies through
  `MarkdownView`;
- a `MarkdownEditor` with a Comment button.

The component keeps the draft when a post fails and shows 429 and validation
errors without echoing the text. The Maintainer and Developer apps use it. A
Reporter app can use it later.

## Risks / Trade-offs

- **Maintainer owner rejects D3 or D4.** The thread would then have to bump the
  version or show plain text. The store and endpoints would not change. Only
  the version rule or the rendering would.
- **Comment spam within the limit.** 10 posts a minute is still a lot. It is
  bounded, nothing is amplified, and Maintainers can see who posted. Moderation
  is a later change.
- **Developer access after reassignment.** A Developer loses access as soon as
  the report is reassigned (DSC-01). That is intentional, but it may surprise
  them. Their earlier comments stay visible to participants.
- **A large thread.** Pagination bounds each response. The client merge is
  bounded by the page size.
- **Wiring the Reporter backend into the deployed app.** This touches the
  shared deployment. Developer and Maintainer `npm run verify` and the auth
  build must still pass.

## Migration Plan

1. Apply `003_report_comments.sql` after `002` on fresh databases, under the same
   policy as SR-01 and D7.
2. Deploy the Reporter and discussion handlers.
3. Each frontend adopts `ReportDiscussion` behind its existing report detail.

Rollback: the table is additive, so removing the handlers leaves the data intact.

## Open Questions

- Should Maintainers be able to hide abusive comments? That is moderation, which
  is out of scope for this version.
- What is the Developer's exact "cannot see" response? Take it from
  `developer-assigned-reports` once that change defines it.
