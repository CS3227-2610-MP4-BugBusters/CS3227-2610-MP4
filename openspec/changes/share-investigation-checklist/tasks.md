# Tasks

## 1. Confirm prerequisites

- [ ] 1.1 Confirm the target application provides authenticated Developer, Reporter, and Maintainer identities, persisted report ownership and assignment, storage migrations, and report detail screens. Verify that a test report can be accessed by its Reporter and reassigned between two Developers before starting checklist implementation.

- [ ] 1.2 Apply the approved CL-05 wording to the Maintainer lifecycle, versioning and MT-11 once PR #1 is integrated. Confirm the shared-only Maintainer read contract for CL-04 with the Maintainer owner. Verify that the checklist and Maintainer specs agree and that the relevant race cases are traceable to MT-11.

## 2. Checklist storage and API

- [ ] 2.1 (CL-01, CL-02) Add per-report draft and shared checklist storage. Verify that a report without checklist data loads with no shared steps and that both versions persist independently.
- [ ] 2.2 (CL-01, CL-02) Add authorized checklist read, save, and share operations. Verify through API tests that the owning Reporter sees no steps before sharing, sees A after sharing A and privately saving B, sees B after sharing B, and still sees B after a rejected empty Share. Verify that direct API Share rejects an empty saved draft without changing the shared copy, report version, or pending proposal. Verify that Reporter writes and cross-report reads are denied.
- [ ] 2.3 (CL-03, MT-06) Use real authenticated API and persistence tests with a saved draft and distinct shared copy. Test direct read, save, and share requests as an unrelated Developer and as D1 after reassignment to D2. Assert denial without content, unchanged copies, and successful draft access by D2.

- [ ] 2.4 (CL-05, MT-11) Enforce lifecycle guards and separate draft revisions from the report version. Verify allowed and denied states, private Save and unchanged Share during review, and changed Share racing with closure in both commit orders using real API and database tests. Check that the persisted checklist, proposal status, and report version match the winning operation.

Local progress: The [checklist migration and private Save](../../../src/backend/developer/README.md) passed PostgreSQL integration checks with a minimal report fixture. The approved current-revision HTTP test also passed in the local harness. Independent verification and the security repair are recorded in the [cycle evidence](../../../logs/developer/2026-11-07-checklist-save-http-cycle-1.md). The stale-revision and former-assignee Save HTTP regression tests also passed in the local synthetic harness. Shared routing and authentication, integrated authorization tests, Share, and the closure race remain open.

## 3. Report detail screens

- [ ] 3.1 (CL-01) Add checklist editing with distinct Save and Share actions to the assigned Developer's report detail screen. Verify that saved steps survive reopening the report and that sharing uses the persisted steps. Verify that the empty-share feedback tells the Developer to add at least one step before sharing.
- [ ] 3.2 (CL-02) Add the read-only shared checklist to the owning Reporter's report detail screen. Verify that only explicitly shared steps are displayed, including after a later private save.
- [ ] 3.3 (CL-04) Use only the authorized shared copy in the Maintainer report detail. Verify through API and UI checks that the Maintainer sees no steps before sharing, sees A after B is saved privately, sees B after it is shared, and still sees B after a rejected empty Share. Check that draft steps are absent from Maintainer API responses.

## 4. Synthetic UI increment

- [x] 4.1 Demonstrate private Save, Share of the saved draft, empty Share feedback, and shared-only Reporter and Maintainer previews using in-memory data. Verify the preview keeps the prior published copy after a private Save. Keep real authentication, assignment, persistence, and race checks in sections 1–3.
