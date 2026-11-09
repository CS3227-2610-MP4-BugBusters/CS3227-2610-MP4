# Checklist Share: real-session integration

## Human-readable summary

- **Goal:** Connect the local checklist publication and shared-read adapters to the authentication application from PR #7, with trusted session identity and CSRF protection.
- **Setup and baseline:** A new real-session test seeds a disposable report and checklist, logs in a Developer and Reporter, then checks Share, shared-only read and denied/CSRF requests. The existing application denied the positive Share request at its default-deny routing boundary. Independent verification reproduced 38 passing tests and one positive Share test failing with 403. This is a missing-route setup blocker, not a verified behavioral Red.
- **Scope and limits:** The user authorized the Share scaffold-baseline exception and requested continued integration. PR #7 was unmerged at integration start. The initial test seeds a draft directly; it does not prove the real Save flow, closure integration, Maintainer permissions, deployment or account-deactivation race behavior. Further results and findings are recorded below after verification.
- **Resource measurements:** Agent token usage, latency and cost are unavailable and must not be inferred.

## Technical evidence

- Starting revision `0c9cfc1`, based on PR #7 head `dafb06543b63508318fb57a2d42c3aa6fc7fbf38` and the local Share commit.
- Test designer `checklist_auth_tests` (GPT-6 Luna low) produced `ChecklistAuthenticatedIntegrationTest.java`. Its initial setup and empty-draft assertions were corrected before independent review. At baseline its SHA-256 was `af60775d71484e65f946e91ea9a1d67b896c47f28b52d974c338fafa1da1d9ce`.
- Independent baseline verifier `checklist_auth_baseline_verify` (GPT-6 Luna low) ran `python3 src/backend/auth/test/run_setup.py` with a disposable PostgreSQL database and loopback access outside the restrictive sandbox. The authenticated Share route was denied with 403; 38 other tests passed. It identified the missing-route setup blocker and coverage limitations.

## Integration candidate results

- Added authenticated routes for private draft Save/read, Share and shared-only read to the PR #7 application. Actor identity and role come from the server-side session; unsafe requests remain behind Spring CSRF. Maintainer shared reads remain denied until a real report-view policy is supplied.
- The test designer and independent baseline verifier confirmed that the original Save and draft-read tests were also blocked at missing routes, not behavioral Reds. A Save test's JSON numeric comparison was corrected after the initial baseline; no claim is made that the corrected bytes produced that baseline.
- A scoped implementer completed the route wiring and report-locked draft read. Independent candidate verification reran `python3 src/backend/auth/test/run_setup.py` against disposable PostgreSQL after each slice; all tests passed, including real-session Save, Share, owning-Reporter shared read, CSRF denials, reassignment and draft privacy. The independent security reviewer inspected each candidate and found no newly introduced critical bypass. Security review did not rerun live probes for these slices.
- Remaining integration gaps include real Maintainer view permission, closure and report-detail services, concurrent account deactivation/revocation versus checklist transactions, and an integrated browser checklist UI. The session freshness check precedes the checklist transaction and does not prove atomic revocation. PR #7 was unmerged at this run.
- Frozen test SHA-256 values: Share `af60775d71484e65f946e91ea9a1d67b896c47f28b52d974c338fafa1da1d9ce`; corrected Save `023d1b343152c43c857c24c6560582cbfd97699c64a85f414506b0c04c1ea672`; draft read `9a97a0a810c0c7a0b650faffceef7e0b14f0a47700f282995724b68ca6aabf48`.

## Context and interpretation for reflection

The team moved from synthetic actors to the shared authentication app, with independently reviewed routing and CSRF checks. A default-deny route prevented the initial positive case from reaching Share.

The initial 403 was a missing-route setup blocker, not a behavioral Red. The successful later tests establish the listed real-session cases only; concurrent revocation and Maintainer access policy remained open at that point.
