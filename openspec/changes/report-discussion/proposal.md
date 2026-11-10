# Proposal

## Why

Reporters, assigned Developers and Maintainers have no way to talk about a report.
A Developer who needs a log or a reproduction detail can't ask the Reporter, and a
Reporter can't add context after filing except by editing an unassigned report.
The Maintainer contracts already expect "one shared history and discussion store
per report", and the Maintainer browser already has a `comments` field, but no
role owns that store and no Reporter or Developer can post.

## What Changes

- **New shared discussion thread per report**, in the style of a GitHub issue
  conversation. The Reporter who filed the report, the currently assigned active
  Developer and any active Maintainer can read it and post to it.
- **Comments are allowed in every status, including `CLOSED`** (owner decision
  10 Nov).
- **Comments don't change the report** (owner decision 10 Nov). Posting never
  changes the report's `version`, `updated_at`, status, assignee or proposal. This
  means a comment never makes a pending Reporter edit fail with `VERSION_CONFLICT`,
  and comments can't push a report up the Maintainer queue.
- **Comment bodies are Markdown**, written with the same Write | Preview
  `MarkdownEditor` and shown with the same safe `MarkdownView` as the description
  (owner decision 10 Nov). Raw HTML, images and links never render.
- **Append-only in this version.** Comments can't be edited or deleted. Each
  comment shows its author's display name, role and time.
- **Limits.** A comment body is at most 20,000 UTF-16 code units. Each author can
  post at most 10 comments per rolling minute (429 `RATE_LIMITED`).
- **Timeline.** Each role's report view shows comments in order with the existing
  history events, such as assigned or closed.
- **Deployment prerequisite.** The Reporter backend (`src/backend/reporter/`) is
  not in the deployed app today: the deployment Dockerfile copies only auth,
  platform, maintainer, socllm and developer code. This change wires the Reporter
  handlers into the deployed app behind real sessions, so Reporters can comment in
  production.
- **Conflicts recorded, not silently overridden.** These owner decisions differ
  from the Maintainer's current plans:
  - `complete-maintainer-role` MNT-DISC-01 specifies plain-text discussion;
  - the Maintainer workflow contract says comments increment the aggregate
    version;
  - `maintainer-report-browser` says every comment renders as escaped text.

  Reconciling them needs the Maintainer owner's approval (task 1.1).

### Out of Scope

Editing or deleting comments, notifications, @mentions, reactions, attachments,
threaded replies, AI-written or AI-summarised comments, comments by Reporters on
other Reporters' reports, and the Developer's private notes and checklist drafts,
which never appear in discussion.

## Capabilities

### New Capabilities

- `report-discussion`: the shared per-report comment thread. Covers who may read
  and post per role, Markdown bodies through `safe-markdown`, the comment and
  timeline shape, validation, the posting rate limit, and the rule that comments
  never change the report's version or lifecycle.

### Modified Capabilities

None in this change's deltas. `maintainer-report-browser` MNT-READ-05 is already
modified by the unarchived change `reporter-markdown-reports`, so a second delta
would conflict. The Maintainer comment-rendering change is made when that change
is archived, as task 1.2 records.

## Impact

- **New shared migration** `src/backend/shared/migrations/003_report_comments.sql`,
  which needs Developer and Maintainer owner sign-off because it is shared.
- **New shared comment store and service** used by three role-specific endpoints:
  - Reporter: `/api/reporter/reports/{id}/comments`
  - Developer: `/api/developer/reports/{id}/comments`
  - Maintainer: `/api/maintainer/reports/{id}/comments`
- **Deployment:** `src/backend/platform/deployment/Dockerfile` and
  `package.gradle` gain the Reporter sources, and the auth server mounts the
  Reporter and comment handlers.
- **Frontends:** the Maintainer and Developer apps each get a `ReportDiscussion`
  component in `src/frontend/shared/markdown`. It reuses `MarkdownEditor` and
  `MarkdownView`.
- **Dependencies:**
  - `reporter-markdown-reports`, for the renderer and the Reporter API;
  - `add-shared-authentication`, for real sessions;
  - `developer-assigned-reports`, for the current assignment;
  - `complete-maintainer-role`, for the history events and the Maintainer report
    detail.
