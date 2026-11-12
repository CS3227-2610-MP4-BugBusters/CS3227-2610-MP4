# Developer browser journey

These Playwright tests check the Developer journey against the packaged
application, real authentication and a disposable PostgreSQL database. It
creates synthetic accounts and an assigned report, then checks:

1. The Developer signs in and opens the assigned report.
2. Starting the investigation changes its status to In Progress.
3. Save persists a private checklist without publishing it; Share publishes it.
4. Submitting a resolution changes the report to Resolution Proposed.
5. A separately authenticated Reporter can read the shared checklist, and the
   report status is checked in the database.
6. Invalid credentials are rejected, and an unassigned Developer cannot see the
   report or read its checklist through the API.
7. An empty private draft cannot be shared: both the UI and direct API reject
   sharing it, and the previously shared checklist remains available.

No AI provider is called. These Chromium tests cover selected authorization and
negative paths; they do not cover concurrency, every role or cross-role Markdown. The
fixture uses temporary credentials, an ephemeral database and random local ports;
the existing packaged journey runner stops and removes them after the test.

## Run locally

Prerequisites: Node and npm, Java and the PostgreSQL `initdb`, `pg_ctl`, `createdb`
and `psql` tools. Run from the repository root:

```sh
cd src/frontend/maintainer && npm ci && npm run build
cd ../developer && npm ci && npm run build
cd ../../backend/auth
./gradlew --no-daemon -I ../platform/deployment/package.gradle authDistribution \
  -PfrontendDist=../../frontend/maintainer/dist \
  -PdeveloperFrontendDist=../../frontend/developer/dist
cd ../../../tests/developer-browser
npm ci
npx playwright install chromium --only-shell
cd ../..
python3 tests/developer-browser/run-journey.py
```

When `initdb` and related tools are outside `PATH`, set `MP4_POSTGRES_BIN` to
their directory. The wrapper supplies test credentials through its child process
environment; don't run the test against a shared or production database. This
test is not yet wired into GitHub Actions.

Future team tests can cover each role's login routing, other denied access, Reporter
creation, Maintainer review, and Markdown parity after the Maintainer renderer
is integrated. Agree ownership and disposable fixtures before adding them to CI.
