# Maintainer frontend

Implemented report browsing, priority and Developer assignment, resolution review,
discussion and queue summaries against the shared authenticated application.
Kept the synthetic development preview explicit and separate from normal startup.

## Run

From the repository root with Node 22.12 or later and npm 10:

```sh
cd src/frontend/shared/markdown
npm ci
cd ../../maintainer
npm ci
npm start
```

`start` uses HTTP and proxies `/api` to `http://127.0.0.1:8080`. Set
`MP4_AUTH_ORIGIN` to use another local backend. Use the
[authentication setup](../../backend/auth/README.md) to provision accounts and
start the application. Production uses same-origin HTTP and has no fake fallback.

Use `npm run demo` for the visibly labelled synthetic preview; its actions and
summary are simulated. Use `npm run verify` for typechecking, component/adapter
tests and a production build. Deployment packaging is documented in the
[deployment README](../../backend/platform/deployment/README.md).

## Integration and content

Mounted `App` with the `Queries` interface in `src/domain.ts`; `src/http.ts` and
`src/action-http.ts` provide bounded, validated HTTP adapters. Recheck identity
on refresh and reject obsolete responses, including responses from adapters
that ignore cancellation. Backend endpoints independently authorize requests.

Connected `@mp4/markdown` for the description only, with its parser worker,
provider and Vite plugin. Rendered lists, tables and code blocks without enabling
raw HTML, image loading or clickable links. Kept titles, remaining source fields,
notes, comments, checklist, proposals and history as escaped text. Wide code and
tables scroll within their own blocks.

Preserved the response limits: 2,000,000 streamed UTF-8 bytes, 20,000 UTF-16 code
units per source string, 500 entries per detail collection, and 100 reports per
page. Discarded unknown response properties and rejected malformed values before
display. See the [browser specification](../../../openspec/specs/maintainer-report-browser/spec.md)
and active OpenSpec changes for the contracts.

## AI and verification limits

Connected queue summaries to the shared SoC adapter and durable usage controls.
Use the [Maintainer backend README](../../backend/maintainer/README.md) for API-key,
model and budget configuration. Without configuration, real generation reports
unavailability; it never substitutes the synthetic summary.

Recorded real-session API/browser checks in
[release checks](../../../logs/maintainer/2026-11-12-release-checks.md) and real AI
trials in [summary repair results](../../../logs/maintainer/2026-11-12-summary-live-repair.md).
These local checks do not establish deployed connectivity, provider quotas or
broad answer quality. Have not verified a live Render deployment.
