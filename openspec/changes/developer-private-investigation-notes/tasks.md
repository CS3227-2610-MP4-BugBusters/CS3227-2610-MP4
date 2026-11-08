# Tasks

Local progress: a synthetic-session HTTP adapter and JDBC notes service have
been exercised against disposable PostgreSQL for default read, save, clear,
stale rejection, proposal preservation and malformed text. Independent local
security probes also covered several access and reassignment cases. Repeatable
synthetic API tests cover denied roles, reassignment access and writes blocked
behind a reassignment or closure transaction. Notes-first cases are sequential,
not concurrent. Real
authentication, account eligibility locking, shared integration and the full
acceptance cases remain open; the tasks below are therefore not complete.

- [ ] 1. Apply the proposed private-notes alignment to the merged Maintainer lifecycle, read projection and MT-11; confirm existing CL-05 checklist wording remains intact and record shared agreement before implementation.
- [ ] 2. Confirm shared session, assignment and report storage integration; decide API route, text limits and error contract. Document report-first locking and the independent notes revision.
- [ ] 3. Follow the project's TDD process for private notes storage and API: default empty read, save, clear with retained revision, stale rejection, state guards and privacy.
- [ ] 4. Independently test direct API and report-detail access for Reporter, Maintainer, unrelated Developer, former assignee and new assignee, including reassignment races and unchanged data on denial.
- [ ] 5. Verify private saves and clears during resolution review preserve the proposal/status/report version; test notes-write versus reassignment and closure in both commit orders with real PostgreSQL and authenticated API integration.
- [ ] 6. Add the notes editor to the agreed report detail UI, preserve unsent input on conflict and render notes as plain text. Run frontend and integration checks.
- [ ] 7. Record human-readable outcomes, agent handoffs, test commands, revisions, limitations and available resource measurements in the reflection evidence.
