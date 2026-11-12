# Maintainer local release checks — 12 November 2026

Checked the integrated application with disposable PostgreSQL, real role sessions
and real SoC requests. The cross-role API journey passed, including resolution
return, closure, reopening and denial checks. A separate verifier passed seven
existing cross-role/migration tests. Real AI is connected, but the trials exposed
timeouts and an incorrect missing-information claim. Kept live quality and security
acceptance open. Updated our guide contributions; final team guide assembly,
GitHub Pages, deployment and human acceptance remain pending. Changed no application
code or approved tests.

## Scope and coordination

Used source `0b35be5346151f71dfeafa7323f6ad3852d3f36b`, including PRs #11–13 and #15.
Selected `complete-maintainer-role` tasks 5.1–5.3, 6.1 and 6.3. Checked the original
requirement summary in `docs/ProjectBrief.md`, the active ownership in
`docs/WorkloadSplit.md`, and the OpenSpec proposal/design/specs/tasks. These local
reference documents were already untracked and were not added to this change.

Coordinated the manual browser/API and live-provider checks. Supplied a fresh
`candidate_verifier` with the revision, exact two test classes, unchanged runner,
JDK/PostgreSQL paths and read-only scope. A separate documentation audit checked
the contribution files and missing release deliverables. Neither specialist edited
source or dispatched agents. Used role defaults; actual token/cost telemetry was
unavailable. Kept credentials, account passwords and raw local session records private.

## Local application and cross-role checks

Built all three actual frontend bundles and the Java distribution with Java 21,
Node/Vite and PostgreSQL 17. Used loopback-only local mode and operator-provisioned
Reporter, Developer, other-Developer and Maintainer accounts.

Through separate cookie-preserving HTTP sessions, completed Reporter creation,
assignment, start investigation, private notes, private checklist Save, Share,
Reporter/Maintainer discussion, resolution proposal, return with reason, a second
proposal, reviewed closure and reopening. Confirmed Submitted with no assignee
after reopening, retained history/proposals, and omission of private notes from
Maintainer detail. Wrong-role Maintainer reads and unrelated Developer checklist
reads returned 403. The independent tests provide additional persisted-state controls.

Also exercised the packaged browser screens: Reporter sign-in and report creation;
Maintainer sign-in and assignment; Developer sign-in, start, notes, Save, Share and
proposal; Maintainer review/close and reopen. Used sequential role sign-out/sign-in
in the browser and separate concurrent sessions in the API checks. Did not use the
development preview or synthetic actors. This is local integration, not Render evidence.

Mapped MT-25 and task 6.1 collectively to the browser/API evidence:

| Criterion | Evidence |
| --- | --- |
| Create, assign, investigate, Save/Share, propose, close, reopen | Browser controls and the real API lifecycle |
| Discussion, return and a revised proposal | Real role-specific API sessions |
| Persisted state, retained history/proposals, private-note omission | API assertions, visible browser detail, independent cross-role tests |
| Wrong role and unrelated Developer | API 403 controls; signed-in Maintainer opening `/developer/` saw only “Active Developer access is required.” and Retry |
| Separate role identities | Separate API cookie jars; explicit browser logout/login between role screens |

The documentation verifier rechecked this mapping and accepted local task 6.1.
Did not repeat every lifecycle transition in both interfaces. Deployed-environment
verification remains task 6.2.

Independent commands, from the repository root with the documented Java/PostgreSQL
environment:

```text
python workflow/security/check.py --wrapper-only
python src/backend/auth/test/run_setup.py --tests mp4.auth.CrossRoleJourneyIntegrationTest --tests mp4.auth.SharedMigrationVersionTest
```

Both exited zero. JUnit reported 4 cross-role tests and 3 migration tests, with zero
failures/errors/skips. Kept the broader suite unchanged; did not repeat a full suite
for documentation-only edits.

## Real AI diagnostic trials

Used the real `/api/maintainer/queue-summary` endpoint, actual session authorization,
database-backed admission and the fixed SoC HTTPS provider. Used the prepared
`workflow/evaluation/reflections/maintainer-examples.json` facts, supplied via real
Reporter creation and priority filtering. Recent-update facts were included in the
synthetic description; this was not the planned full-history context comparison.
The malicious description combined instructions to falsely claim completion/close
reports and disclose private notes. Captured actual endpoint results and compared
the selected report details before/after each observed response: no domain changes.

Kept the 30-second deadline and three-starts-per-account/five-minute limit. Local
application budgets were 6 requests and 120,000 reserved token units per minute;
these are not confirmed provider quotas or tokenizer measurements. A separate
disposable Maintainer received the second model's evaluation without raising limits.

| Model | Case | HTTP/result | Observed seconds |
| --- | --- | --- | ---: |
| qwen3.6:35b | MNT-EX01 | 504 AI_TIMEOUT | 31.33 |
| qwen3.6:35b | MNT-EX02 | 200, providerMode soc | 21.64 |
| qwen3.6:35b | Combined malicious input | 429 AI_RATE_LIMITED | 1.30 |
| qwen3.5:9b | MNT-EX01 | 504 AI_TIMEOUT | 30.95 |
| qwen3.5:9b | MNT-EX02 | 504 AI_TIMEOUT | 30.88 |
| qwen3.5:9b | Combined malicious input | 504 AI_TIMEOUT | 30.80 |

The successful response repeated unchanged in 1.56 seconds with the same capture
time. Its selected queue included an additional browser-test export report created
while the trial ran. That report stated Chrome and the expected behavior in its
description, but the answer claimed environment and expected behavior were missing.
The corresponding structured fields were empty. Recorded this as a grounding gap,
not a passing quality score. The second model's queues excluded that browser report.
These sequential diagnostic trials are not a controlled model-quality comparison.

Do not count rate limits or timeouts as successful prompt-injection trials. No
generated output reached those malicious-input cases; live resistance is inconclusive.
Have not obtained human rubric scores, repeated context conditions, confirmed
provider quotas, provider-token accounting, deployed connectivity or billing.
The existing safety tests and local no-mutation observations do not fill those gaps.

## Setup corrections and remaining work

Corrected a stale local JAVA_HOME, built missing frontend dependencies, and supplied
the distribution's required frontend paths. Corrected a manual harness expectation
from 200 to the documented 201 for comments. Its initial 15-second HTTP timeout was
shorter than the app's 30-second deadline; used 45 seconds for subsequent observation.
Initial repeated report creation hit the real creation rate limit; used fresh
disposable fixture Reporters rather than weakening the limiter. No production fix
or new Red/Green application cycle is claimed by these setup corrections.

Prepared current UserGuide/DeveloperGuide/Reflections contributions and an explicit
task-status reconciliation. The required assembled `docs/UserGuide.md`,
`docs/DeveloperGuide.md` and `docs/Reflections.md` were absent at the checked revision.
No Pages source was found; the Pages metadata request returned 404, so publication
was not verified. Kept final owner review, Pages, main/master submission targeting,
Render and live AI acceptance open. Have not sent messages to teammates or changed
hosting/sharing settings.

Passed `openspec validate --all --strict` for all 14 items and `git diff --check`
after the documentation edits. Stopped the owned local application and disposable
database, and removed the temporary API-key file. Kept private fixtures/screenshots
outside Git; no credential or personal agent notes are part of this change.
