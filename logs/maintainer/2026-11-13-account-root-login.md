# Account repair and common sign-in return

Restored the Account header, role navigation and sign-out button after the profile
merge left them hidden. Removed the duplicate identity element; the header now
shows the display name once and the account section shows username and role.
Display-name updates retain that presentation. Demo restrictions and existing
account forms remain governed by the existing account response.

Removed Developer and Reporter credential forms. Signed-out states return to the
root sign-in page, including successful logout and propagated session loss. Both
role apps retain recoverable logout errors. Reporter previously treated failed
logout as success through a finally block; corrected that behavior.

Developer and Reporter verification passed, including 70 and 25 tests respectively,
typechecks and builds. Account DOM checks passed for all roles, profile updates,
demo restrictions, anonymous access and logout success/failure. Rebuilt and
refreshed the local Docker app. Live resources match the verified builds, and a
real demo session passed login, Account read, CSRF logout and subsequent denial.
Browser access was unavailable, so visual, keyboard and actual browser navigation
remain unverified. The owner explicitly requested no TDD; no independent Red or
candidate review is claimed.

## Implementation and coordination

- Starting revision: `2de21667158da7b796af9bae71393c8e8b28d4f6`, clean `main`.
  Tested the uncommitted worktree changes described here.
- Scope: shared-role-ui follow-up task 3.3, relating to Account task 1.5 and root
  entry task 3.2. Read its proposal, design, spec and tasks; read the AUTH-05/06/07
  session/CSRF criteria and relevant role applications and tests.
- Initially dispatched `test_designer` with scoped criteria and test-only edit
  permission. The owner then said "dont use tdd". Interrupted the designer before
  any test artifacts appeared, and implemented directly in the coordinator.
  No further specialists or independent reviews ran. New checks and existing
  test adaptations were written after the implementation, not as a Red gate.
- Existing tests required local Developer/Reporter login forms. Updated them to
  the owner's requested root-only entry; retained session-before-data, CSRF logout,
  protected-content cleanup and new-session query isolation coverage.
- Server authorization, persistence, password policy and CSRF code were not edited.

## Findings and fixes

- Account HTML had two `identity` IDs and the merged profile initializer did not
  reveal the shared shell. Centralized Account presentation in `showAccount`,
  reused after loading and display-name changes.
- Removed local credential state and submit handlers from both role screens;
  signed-out effects use a literal `/` destination and replace browser history.
- Account uses the same root return after successful logout, password change or
  a 401; successful logout still follows its CSRF-protected POST.
- Reporter logout failures now display a retryable error and keep its sign-out
  button available rather than claiming successful logout.
- Fixed a trailing blank line identified by diff checking.
- The initial urllib authentication smoke failed with 403 because its cookie jar
  withheld the server's Secure session cookie on loopback HTTP. Rechecked using
  explicit in-memory cookie forwarding for this harness only; no server cookie
  or transport setting was weakened. This is API evidence, not browser transport
  verification.

## Verification and local refresh

- `npm.cmd run verify`, `src/frontend/developer`: PASS, 14 files / 70 tests,
  typecheck and production build. Vitest reported 28.15 seconds.
- `npm.cmd run verify`, `src/frontend/reporter`: PASS, 2 files / 25 tests,
  typecheck and production build. Vitest reported 18.98 seconds.
- `node src/backend/auth/test/account-page.cjs`: PASS. Uses the actual Account
  HTML/JS with jsdom and synthetic API replies. Covers all roles, dashboard links,
  single identity, header visibility, profile edit, demo restrictions, anonymous
  redirect, CSRF logout, failure recovery and protected-content hiding.
- `node --check src/backend/auth/src/main/resources/static/account/account.js`:
  PASS. `openspec.cmd validate shared-role-ui --strict`: PASS.
- `git diff --check`: PASS after whitespace correction.
- Docker build from the worktree: PASS, including all frontends and Java packaging.
  Image manifest list: `sha256:457f5f0a99e6bb61092f6130802fbbe4138eb3e9643fe39c6aeee9fd7db185b9`.
  Revision asset records the base commit, not the uncommitted UI changes; compared
  served Account HTML/JS and both role bundles directly with the worktree/builds.
- Replaced only `mp4-local-app`; retained its environment and Java launch command,
  network, loopback port 18082 and existing PostgreSQL container/data. Previous app
  retained stopped as `mp4-local-app-backup-20261113123632` for rollback.
- Live HTTP: health, root, Developer, Reporter, Account and shared Account CSS all
  200; exact Account HTML/JS and Developer/Reporter bundle comparisons PASS.
- Live existing public demo account: login 200, Account read with Maintainer and
  demo lock, CSRF logout 204, subsequent Account 401. Used explicit loopback cookie
  forwarding as described above. No account/report writes performed.
- CUA browser attempt reported "The browser access host is unavailable". No actual
  browser layout, keyboard, Developer/Reporter real-session browser journey or
  hosted deployment is claimed. Broader task 3.2 remains open.

## Reflection and resources

The coordinator completed implementation, regression updates, review and refresh
after the owner's workflow override. The interrupted designer produced no adopted
artifacts. No independent review is claimed. Model/effort, input/cached/output/
reasoning tokens, cost and sanitized trace locations are unavailable; no resource
savings are inferred. Vitest timings above measure their suites only.

Timeline (Asia/Singapore, 2026-11-13): frontend suites started at 12:34:02 and
12:34:11; Account DOM and build checks followed; local app replacement completed
at approximately 12:36:32, followed by live resource and session smoke checks.
Earlier dispatch/interrupt timings were not captured as separate measurements.
