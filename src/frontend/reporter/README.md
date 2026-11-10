# Reporter app

The Reporter's web app, served at `/reporter/`. Its layout follows familiar issue-tracker conventions:
- a report list with open and closed state icons, status labels and a title filter;
- a report page with an open/closed badge, a timeline whose first entry is the description, the comments after it, and a sidebar with details;
- a Write | Preview comment box at the bottom.

## What a Reporter can do

- **Sign in.** Only an active `REPORTER` account gets past the sign-in page. Other roles are refused before any report data is requested.
- **List their own reports.** 25 per page, newest first. The Open, Closed and title filters apply to the loaded page only, because the API has no status filter.
- **Create a report.** The description starts from a short template and uses the shared `MarkdownEditor`.
- **Edit the title or description** while the report is `SUBMITTED`. The server also requires that the report has no assignee. A refusal shows "can't be edited any more" (`REPORT_LOCKED`) or "changed since you opened it" (`VERSION_CONFLICT`).
- **Read and post comments.** Markdown renders through the shared `MarkdownView`, parsed in the shared worker. Comments are allowed on closed reports.

Two rules hold throughout:
- **A failed submit keeps the draft.** Error messages never repeat the submitted text.
- **Rate limits show the wait time.** A 429 says how many seconds to wait, from `Retry-After`.

## APIs used

| Call | Contract |
| --- | --- |
| `/api/auth/me`, `/api/auth/csrf`, `/api/auth/login`, `/api/auth/logout` | shared authentication |
| `GET`/`POST /api/reporter/reports`, `GET`/`PUT /api/reporter/reports/{id}` | `workflow/reporter/reporter-reports-api-contract.md` |
| `GET`/`POST /api/reporter/reports/{id}/comments` | `workflow/reporter/report-discussion-api-contract.md` |

Every write sends the `X-CSRF-TOKEN` header.

## Develop and verify

```sh
cd src/frontend/shared/markdown && npm ci     # the file: dependency needs its own node_modules
cd ../../reporter && npm ci
npm run dev       # proxies /api to 127.0.0.1:8080
npm run verify    # typecheck, vitest, production build
```

`vite.config.ts` does three things:
- It uses the shared package's `markdownVite()` plugin, so the Markdown worker builds without `document`.
- It dedupes React, because `@mp4/markdown` has its own `node_modules`.
- It allows the dev server to read `../shared`.

## Deployment

- The deployment `Dockerfile` builds this app in its `reporter-frontend` stage.
- `package.gradle` copies the `dist/` output to `static/reporter` when `-PreporterFrontendDist` is set.
- `AuthConfiguration` forwards `/reporter/` to its `index.html` and allows anonymous `GET` on `/reporter/**`. The data itself is protected by `/api/reporter/**`, which requires `hasRole("REPORTER")`.
- Routes use the URL hash (`#/`, `#/new`, `#/reports/{id}`), so the server never needs to forward deep links.
