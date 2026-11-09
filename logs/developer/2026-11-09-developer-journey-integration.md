# Developer checklist journey: local integration

## Human-readable summary

- **Goal and changes:** Connect the Developer sign-in, assigned-report list and checklist screen to the real-session application from PR #7. The checklist can reload its draft, save privately and share the saved version. The draft read now includes the report version needed for a safe Share.
- **Checks:** Real-session PostgreSQL tests passed for draft read, Save, Share, owning-Reporter shared read, CSRF and access denials. The Developer frontend passed typecheck, 15 component tests and a production build. A packaged local application passed HTTP checks for login, assigned list, draft read, Save, Share, Reporter shared read and denial controls. An independent verifier reran the packaged HTTP journey. This was not a browser test.
- **Review findings and fixes:** An independent UI review found that a failed read or a late request after switching reports could leave another report's private state visible or actionable. Regression tests reproduced the issues; a keyed screen, successful-load gate and cancellation guards fixed them, and independent checks passed. A real-session regression also reproduced a draft revision exceeding JavaScript's safe integer range; Save now rejects that case without mutation, and independent verification and security review passed.
- **Packaging:** The local distribution contains the Maintainer app at `/` and the Developer app at `/developer/`. A separate local packaged-server smoke test and independent rerun verified static assets, CSRF/login reachability and anonymous protected-API denials. Docker was not available locally. The workflow adds Share runners and checks the Developer image path; hosted results must be checked after push.
- **Hosted CI correction:** On PR #9, the first hosted run failed both assigned-report jobs because the draft store referenced a Share-only constant absent from that runner's compile inputs. The failure was reproduced independently. The safe-integer limit was moved to the independently compiled draft store, with Share using an alias; local assigned-report, auth and Share regression checks passed. The corrected hosted result must be verified separately.
- **Limits:** The tested packaged journey manually applies the report and checklist migrations to a disposable database. Production migration registration, a real Maintainer view-permission policy, closure/report-detail integration, concurrent account revocation, real browser verification and deployment remain open. The test fixtures and temporary accounts are not deployment evidence. Auth PR #7 was unmerged at integration start.
- **AI:** The environment exposed SoC configuration variables, and two bounded read-only model-list requests succeeded. No prompt, user report or credential was logged, and no live generation is claimed here.
- **Resources:** Specialized test designers, implementers, verifiers and security reviewers used scoped handoffs. Actual per-agent tokens, costs and elapsed times were unavailable; do not infer savings.

## Technical evidence

- Starting integration commit `540bbe3258958d9d97f7aa2bc24c4f0a32fe0a61`, based on PR #7 head `dafb06543b63508318fb57a2d42c3aa6fc7fbf38` plus Share work. Candidate files were uncommitted when tested.
- Original draft-read test hash `9a97a0a810c0c7a0b650faffceef7e0b14f0a47700f282995724b68ca6aabf48`; after the report-version contract update, independently reproduced behavioral Red and Green used `090d75b24b22068062e801c092e0a8688f9edaecbf5422078fc71ed73eb4dc55`.
- Save-limit regression `ChecklistAuthenticatedSaveVersionLimitIntegrationTest.java`: `dbe6baa0d9f020f4ae290e9fc296520557f961933d6f6c8538d1b86272a765af`. Independent Red showed an unsafe increment; candidate and security verification confirmed 409 and unchanged database state.
- Frontend `DeveloperChecklist.test.tsx`: `e5a9b0df4d7fa11773c77ac887de254aabe9af5dcf0ab7e012fcc6bd8b009122`; isolation regression `DeveloperChecklistIsolation.test.tsx`: `779043f5e9b4fa9e59560b0522ec17f79f855e7e49955552c249f5e9e51ad899`. Independent verifier reran `npm run verify` after the fix.
- Packaging smoke runner `run-packaged-developer-smoke.py`: `3d7e312455a4d681135a6bbaeb8e6d8551a953947166af3acea4292c2bded645`; real-session HTTP journey runner `run-packaged-developer-journey.py`: `84eeeaedc2e3f9bf779b0865c24c5690964a19e5c8cddd7efe84049959738a2f`. Both ran against a temporary PostgreSQL server and packaged app with loopback escalation; independent packaging reviewer reran each.
- Coordination: implementation used GPT-6 Luna high; test design and independent candidate verification used GPT-6 Luna low; security review used GPT-6.1 Sol medium. Test and runner corrections and their limits are described above rather than conflated with original baseline evidence.

## Context and interpretation for reflection

The integration work joined real sessions, checklist operations, UI and a packaged HTTP journey. Separate reviewers identified UI isolation and safe-integer issues, and the hosted run exposed a compile-input dependency.

The HTTP journey was not a browser test and initially used manually applied migrations. Later startup migration evidence is recorded in `2026-11-09-shared-startup-migrations.md`; deployment remained outside this run.
