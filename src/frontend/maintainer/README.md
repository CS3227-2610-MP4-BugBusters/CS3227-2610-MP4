# Maintainer report browser

This isolated React/TypeScript package provides the read-only HTTP report browser and a development-only synthetic demo. The demo supports an in-memory, versioned priority update for exercising the Maintainer UI. It does not add a real write endpoint, server authorization, persistence, or AI calls.

## Run

From this directory with Node 22.12 or later and npm 10:

```sh
npm ci
npm start
npm run demo
npm run verify
```

`start` uses same-origin HTTP endpoints. Configure the owning application's development server/reverse proxy to provide those endpoints; this package supplies no mock server. `demo` explicitly selects synthetic development data and displays a banner. Production builds always select HTTP and have no automatic synthetic fallback.

## Proposed integration boundary

Mount `<App queries={queries} />` with the `Queries` interface in `src/domain.ts`. `src/http.ts` supplies the proposed GET adapter. The optional `updatePriority` capability is implemented by the explicit demo adapter only; adapters without it remain read-only and show no priority editor. Demo updates operate on private copies per adapter, require a matching version, append a priority history entry and return the safe summary projection. All operations receive cancellation signals. Refresh rechecks current identity before protected data. Query generations also reject stale results from cancellation-ignoring adapters. The UI guard does not prove server authorization: real endpoints must independently authorize every request.

Proposed payloads follow `openspec/specs/maintainer-report-browser/spec.md`. Parse all nested values before display. Bound HTTP responses to 2,000,000 UTF-8 bytes while streaming; cancel oversized streams. Strings are bounded to 20,000 UTF-16 code units, titles/names/type to 200, timestamps to 40 and UUIDs to 36. Bound each detail collection and developer list to 500 entries and page items to 100; versions/counts must be nonnegative safe integers. Projection discards unknown response properties. Filters reject unknown properties before transport, with a default limit of 50 and cap of 100. Render source content as escaped plain text.

## Verification and limits

Independent review identified a QR-02 timestamp-ordering defect. Replaced string ordering with numeric instant ordering and added fractional/equivalent timestamp regression coverage; independent repair verification passed.

Tests cover the read-only browser and the isolated priority UI/demo seam, including nested payload rejection, transport construction, intersecting filters, errors, identity handling and cancellation-ignoring races. The production bundle and explicit entry gating support QR-08. Shared DTO agreement, live service integration, write authorization/CSRF, database behavior, and deployment remain pending. No live AI calls or invented resource metrics are involved.

Verification on 7 November 2026: clean `npm ci`, typecheck, 30 tests and production build passed independently. Chrome desktop/mobile smoke checks passed pagination, combined filters, empty states and safe text rendering. A production-entry smoke check used intercepted synthetic HTTP responses; this does not establish real backend integration. `npm audit` reported no known vulnerabilities at the checked lockfile. Full evidence is in `logs/maintainer/2026-11-07-read-only-increment.md`.
