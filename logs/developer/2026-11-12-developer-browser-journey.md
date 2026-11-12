# Developer browser journey

## Summary

Added a project-local Playwright Test for the Developer's assigned-report
journey, using the existing packaged-app runner's disposable database and
synthetic accounts. It exercises sign-in, starting work, saving and sharing a
checklist and proposing a resolution. Additional cases check invalid sign-in,
unassigned Developer access and empty-share protection. The Reporter reads the
shared checklist through a separate real session. The browser and original HTTP journeys are
verified separately; this test is not yet part of CI.

## Implementation and coordination

The starting revision was main `835a967439f1b5fe735e85890f50bc72014bf29a`.
The coordinator used the lightweight path because this adds verification rather
than changing application behavior. Context included the checklist Share spec,
the Developer frontend, and the existing packaged HTTP journey. No specialist
agents were dispatched. Existing HTTP setup was reused via an explicit callback.

## Findings and fixes

The first browser run could not start because Chromium was not installed. After
installing the Playwright headless browser, the test reached the UI and found
its Step 1 label selector also matched the remove button; an exact label fixed
the test selector. These were test setup issues, not application failures.

## Verification

The packaged application used a temporary PostgreSQL cluster and generated
accounts. `python3 tests/developer-browser/run-journey.py` passed: three browser
tests, direct denial and empty-share API checks, the Reporter's published-checklist
read, and persisted report status.
`python3 src/backend/platform/deployment/test/run-packaged-developer-journey.py`
also passed after the fixture callback change. `git diff --check` passed.
The tests do not exercise a live AI provider, deployed app, cross-role Markdown,
all authorization cases, or every browser. See the README for the build commands.

## Reflection and resources

The existing disposable setup reduced duplicated authentication and database
provisioning logic. Browser tests cost more setup and time than component tests
but verify the packaged UI, HTTP session and storage together. Token usage,
cache measurements and monetary cost were unavailable and are not estimated.
