# Tasks

Every behavior task follows [tdd.md](../../../workflow/tdd.md):
1. `test_designer` writes the Red test.
2. A fresh `baseline_verifier` checks it.
3. The feature owner approves.
4. The `implementer` makes it pass.
5. A fresh `candidate_verifier` checks the result.

Tasks marked **[sec]** also need a separate `security_reviewer`.

Group 2 depends on 1.1 and 1.3. Group 3 depends on group 2 and on
`reporter-markdown-reports` group 3 (`safe-markdown`). Group 4 depends on the
matching role's report detail screen.

## 1. Approvals, contract and deployment

- [ ] 1.1 Get Maintainer owner approval for design D3 (comments don't change the aggregate version, which conflicts with the workflow contract) and D4 (Markdown bodies, which conflicts with MNT-DISC-01 and the `maintainer-report-browser` escaped-comment rule). Get Developer and Maintainer owner sign-off for the shared migration `003_report_comments.sql`. Verify by recording each approver and date in `logs/reporter/`.
- [ ] 1.2 Once `reporter-markdown-reports` is archived, add a `maintainer-report-browser` delta to this change. It makes comment bodies render through `safe-markdown`, and it amends the "Render every … comment … as escaped text" prose. Verify with `openspec validate report-discussion --strict`.
- [ ] 1.3 Write `workflow/reporter/report-discussion-api-contract.md` from DSC-01..06 and design D1–D6:
  - the three paths and methods;
  - the request body `{body}`;
  - the comment and page JSON;
  - the per-role "cannot see" responses;
  - the validation, 405 and 429 rules with `Retry-After`;
  - the error envelope and `no-store`.

  Verify that the feature owner approves it before 2.2 Red starts. After approval, it is read-only for every agent.
- [x] 1.4 **[sec]** Wire the Reporter backend into the deployed app (D7):
  - add `../reporter/src` to `auth/build.gradle`;
  - copy it in the Dockerfile;
  - mount the Reporter handler in `AuthController` with the trusted session actor.

  Verify with:
  - an auth-server integration test showing that `/api/reporter/reports` uses the session actor and ignores identity headers;
  - the auth build passing;
  - the Reporter 2.3–2.11 harness tests still passing;
  - `docker build` succeeding.

## 2. Discussion backend (`report-discussion`)

- [ ] 2.1 Red/Green migration `src/backend/shared/migrations/003_report_comments.sql` (D1):
  - the table, the checks and both indexes;
  - the foreign key to `reports`;
  - it applies after `001` and `002` on a fresh database.

  Verify with `report_comments_migration_test.py` via the Reporter disposable-PostgreSQL runner, plus the existing shared-storage suite still passing.
- [ ] 2.2 **[sec]** Red/Green DSC-01, DSC-02 and DSC-04 through the shared service and the Reporter endpoint. Verify with `report_discussion_api_test.py`, covering:
  - post and read by the owner;
  - the RPT-05 404 for other Reporters, a missing ID and a malformed ID;
  - a spoofed author giving 400;
  - 401 and 403;
  - order and shape;
  - pagination;
  - 405 for PUT, PATCH and DELETE;
  - a comment on a `CLOSED` report;
  - `version` and `updated_at` unchanged after posting;
  - a Reporter edit with the old version still succeeding after a comment.
- [ ] 2.3 **[sec]** Red/Green DSC-03 body validation and DSC-05 rate limit (D5). Verify with `report_discussion_validation_test.py` and `report_discussion_rate_limit_test.py`, covering:
  - the 20,000/20,001 boundary, U+0000, an unpaired surrogate, a blank body and extra keys;
  - an exact round trip of the body;
  - the 11th post returning 429 with `Retry-After`;
  - 8 seeded posts plus 5 concurrent posts giving exactly 2 accepted;
  - the limit applying per author across reports;
  - invalid and 429 requests not counting.
- [ ] 2.4 **[sec]** Red/Green the Developer and Maintainer endpoints (DSC-01, DSC-06):
  - assigned-Developer access;
  - reassignment removing the old Developer's access while keeping their comments;
  - Maintainer access in every status;
  - the "cannot see" response for each role;
  - no private notes, draft steps or draft revision in any response.

  Verify with `report_discussion_roles_test.py` against the auth-server integration setup from 1.4.

## 3. Shared discussion component (`safe-markdown` consumer)

- [ ] 3.1 **[sec]** Red/Green `ReportDiscussion` in `src/frontend/shared/markdown` (D6, D8):
  - the timeline merge order;
  - names and times as escaped text;
  - bodies through `MarkdownView`;
  - the `MarkdownEditor` Write | Preview, where the preview equals the posted render;
  - the draft kept on failure;
  - 429 and validation messages without echoing the text;
  - the DSC-03 hostile-body fixture showing no `img`, `a`, `script` or `on*` in the output.

  Verify with vitest and `npm run verify` in the package.

## 4. Role adoption

- [ ] 4.1 **[sec]** Red/Green the Maintainer report detail using `ReportDiscussion` (after 1.1 and 1.2). Verify with `App.test.tsx` and `npm run verify` in `src/frontend/maintainer`.
- [ ] 4.2 **[sec]** Red/Green the Developer report detail using `ReportDiscussion`. Verify with Developer tests that assert the same DOM as the Maintainer for the DSC-03 fixture, and with `npm run verify` in `src/frontend/developer`.

## 5. Cross-role integration

- [ ] 5.1 Verify end to end on one revision:
  1. A Reporter posts a Markdown comment through the deployed app.
  2. The assigned Developer replies.
  3. A Maintainer replies after closing the report.

  All three views render the thread identically, and the report `version` is unchanged throughout. Record the commands, revision and outcome in `logs/reporter/`.
- [ ] 5.2 Run `openspec validate report-discussion --strict --no-interactive` and every touched package's verify command on one revision. Record the results in `logs/reporter/`.
