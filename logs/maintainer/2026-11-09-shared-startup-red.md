# Shared startup baseline evidence

The three PostgreSQL startup cases compiled and produced behavior-specific Red results. Fresh auth-only startup did not create `reports`; legacy startup did not add the required Maintainer report columns; and startup accepted a partial `reports (id)` table. Existing Maintainer HTTP integration tests also remained red. The test class owns randomly named databases and drops each when the test exits. No implementation or runner files changed. An independent baseline verifier must reproduce these results before implementation proceeds.

## Technical evidence

- Task: `complete-maintainer-role` MNT-1.2/MNT-1.3 shared-storage startup integration.
- Starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Role: `test_designer`; configured effort: high; exact model identifier: unavailable in this task context.
- Test: `src/backend/auth/src/test/java/mp4/auth/SharedSchemaStartupIntegrationTest.java`.
- Runner/fixtures: existing auth Gradle/PostgreSQL setup; each case creates its own random disposable database through the configured PostgreSQL test account. Legacy case seeds the canonical `001_create_reports.sql` eight-column shape and `create_investigation_checklists.sql` shape explicitly.
- Command: `$env:MP4_POSTGRES_BIN = 'C:\Program Files\PostgreSQL\17\bin'; $env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot'; python src/backend/auth/test/run_setup.py`.
- Runtime: JDK 21.0.5; PostgreSQL 17 tools; isolated disposable PostgreSQL cluster managed by the unchanged runner.
- Result: 104 tests completed; 13 failed; 91 passed. The 10 `MaintainerAuthenticatedWorkflowIntegrationTest` cases failed as existing HTTP behavior Red. All 3 new startup cases failed on their expected missing-schema behavior. Gradle reported no compile errors or test errors. The legacy case checked required report columns before querying them; partial-schema startup returned normally and the test closed the server before its assertion failed.
- Initial setup attempt used an inherited invalid `JAVA_HOME` and failed before Gradle. I corrected it to the installed JDK 21 and reran the unchanged Python runner. Both runner invocations cleaned up their isolated clusters.
- Test SHA-1 object hash: `6e39f85204db8886a30eafa0fa1525245883ac9e`.
- Open: fresh `baseline_verifier` reproduction remains required; implementation and candidate verification remain pending.
