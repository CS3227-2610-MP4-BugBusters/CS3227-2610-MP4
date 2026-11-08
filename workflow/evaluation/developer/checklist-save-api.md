# Authenticated checklist Save API verification

The current-revision test can run against the local HTTP harness and disposable PostgreSQL fixture. Verification in the integrated application requires its supported Developer session and a fixture compatible with its schema.

## Local database fixture

The following commands verify the database setup independently of HTTP. Point psql at a fresh disposable database through `PGDATABASE` and the usual PostgreSQL connection settings. Use distinct synthetic UUIDs for the report and Developer. When connecting to an integrated app, use the authenticated test Developer's ID and the app's schema.

```sh
export MP4_TEST_REPORT_ID=00000000-0000-0000-0000-000000000301
export MP4_TEST_DEVELOPER_ID=00000000-0000-0000-0000-000000000401
export MP4_TEST_SNAPSHOT_SQL=src/backend/developer/test/checklist-save-snapshot.sql
psql -X -v ON_ERROR_STOP=1 -v report_id="$MP4_TEST_REPORT_ID" \
  -v developer_id="$MP4_TEST_DEVELOPER_ID" \
  -f src/backend/developer/test/report-fixture.sql \
  -f src/backend/developer/migrations/create_investigation_checklists.sql \
  -f src/backend/developer/test/checklist-save-fixture.sql
psql -XqAt -v ON_ERROR_STOP=1 -v report_id="$MP4_TEST_REPORT_ID" \
  -f "$MP4_TEST_SNAPSHOT_SQL"
```

The fixture commits a pending proposal row, report version 7, and draft revision 2. These SQL files create test data only. Use the app's migrations and a compatible seed/snapshot pair when running the actual HTTP test.

Verified on 7 November 2026: the seed and migration ran against disposable PostgreSQL 17. The Python test accepted the actual snapshot from a read-only connection, including the persisted proposal.

## Local HTTP baseline

After seeding the disposable database above, run from the repository root with JDK 17 or newer. The harness starts on an ephemeral IPv4 loopback port. The runner generates a test-only session token and checks that the control endpoint accepts the session and resolves the Developer seeded in the fixture.

```sh
(
  set -e
  build_dir=$(mktemp -d)
  trap 'rm -rf "$build_dir"' EXIT
  javac --release 17 --add-modules jdk.httpserver -d "$build_dir" \
    src/backend/developer/src/ChecklistDraftStore.java \
    src/backend/developer/test/ChecklistSaveHttpHarness.java
  python3 - "$build_dir" <<'PY'
import json
import os
import secrets
import subprocess
import sys
from http.client import HTTPConnection
from urllib.parse import urlsplit

os.environ["MP4_TEST_SESSION_TOKEN"] = secrets.token_urlsafe()
headers = {"Authorization": "Bearer " + os.environ["MP4_TEST_SESSION_TOKEN"]}
os.environ["MP4_TEST_SESSION_HEADERS"] = json.dumps(headers)
server = subprocess.Popen(
    ["java", "--add-modules", "jdk.httpserver", "-cp", sys.argv[1],
     "mp4.developer.ChecklistSaveHttpHarness"],
    stdout=subprocess.PIPE, text=True,
)
try:
    origin = server.stdout.readline().strip()
    if not origin:
        raise SystemExit("Setup blocked: harness did not start")
    os.environ["MP4_TEST_ORIGIN"] = origin
    address = urlsplit(origin)
    connection = HTTPConnection(address.hostname, address.port, timeout=5)
    try:
        connection.request("GET", "/__test/session")
        response = connection.getresponse()
        assert response.status == 401, f"Expected anonymous GET 401, got {response.status}"
        response.read()
        connection.request("GET", "/__test/session", headers=headers)
        response = connection.getresponse()
        assert response.status == 200, f"Expected authenticated GET 200, got {response.status}"
        assert json.load(response) == {
            "id": os.environ["MP4_TEST_DEVELOPER_ID"], "role": "DEVELOPER", "active": True,
        }, "Harness resolved a different actor"
    finally:
        connection.close()
    print("Local session control passed", flush=True)
    raise SystemExit(subprocess.run(
        [sys.executable, "src/backend/developer/test/checklist_save_api_test.py"],
    ).returncode)
finally:
    server.terminate()
    server.wait(timeout=5)
PY
)
```

The baseline starts without an application handler, so the expected Red is `Expected HTTP 200, got 404` after the session control and fixture checks pass. A test launcher can call `ChecklistSaveHttpHarness.start(handler)` to mount the actual HTTP handler and receive the resolved synthetic actor with each authenticated request. This run covers local HTTP wiring and the disposable database fixture. Production authentication and the shared schema require integrated verification.

## Cycle 1 — Current draft revision

For integrated verification, use a disposable PostgreSQL database with the application's identity, report, proposal, and checklist migrations. Create an active Developer D1 and authenticate through the application's supported test login or session bootstrap. Persist a report assigned to D1 in Resolution proposed at report version 7, with a current pending proposal P whose identity, version, and contents are known. Seed the following ordered steps at draft revision 2.

- Shared steps A — `["Read the incident report", "Check the logs"]`
- Private draft B — `["Compare timestamps", "Review the first trace"]`

Commit the fixture and keep other writers off it. Start the real application against the disposable database. Configure these environment variables in the test shell.

| Variable | Value |
| --- | --- |
| `MP4_TEST_ORIGIN` | HTTP(S) origin of the disposable application |
| `MP4_TEST_SESSION_HEADERS` | JSON object of authenticated session headers for D1, including any required CSRF header |
| `MP4_TEST_REPORT_ID` | Fixture report UUID |
| `MP4_TEST_DEVELOPER_ID` | D1's UUID |
| `MP4_TEST_SNAPSHOT_SQL` | Path to a read-only psql script for the integrated schema |
| `PGDATABASE` | Disposable database name or connection URI used by psql. Set other PostgreSQL connection variables as needed. |

The SQL script takes the psql variable `:'report_id'` and emits one JSON object for that report. Project ordered `draftSteps` and `sharedSteps` arrays and numeric `draftRevision` at the top level. Include `report` with `assigneeId`, `status` (normalize to `RESOLUTION_PROPOSED`), numeric `version`, and `currentProposalId`. Include `proposal` with the persisted proposal's `id`, `status` (normalize to `PENDING`), numeric `version`, and `contents` covering the stored proposal contents. Additional fields within `report` and `proposal` are included in the preservation comparison.

Run from the repository root.

```sh
python3 src/backend/developer/test/checklist_save_api_test.py
```

The test snapshots the committed fixture, then sends `PUT /api/reports/{reportId}/checklist/draft` with D1's authenticated session and `{"steps":["Reproduce the bug","Capture a new trace"],"expectedDraftRevision":2}`. It expects HTTP 200 with `{"draftRevision":3}`. It opens a fresh database connection and verifies the ordered draft and incremented revision, the original shared copy, and the preserved report and proposal records. Reset the fixture before rerunning.

An authenticated, verified fixture is required for a valid Red. An unavailable target, unverified routing or auth setup, missing session, or unavailable database blocks execution. With the fixture and application wiring verified, a failure attributable to the Save behavior is a valid Red.

## Cycle 2 — Stale draft revision

Run this separately after Cycle 1 passes. Restore the committed fixture to its baseline or use an equivalent isolated fixture. Send the same authenticated PUT request and candidate steps, changing only `expectedDraftRevision` from 2 to 1.

1. Assert HTTP 409 and `code: "STALE_VERSION"` in the `{ code, message, requestId }` JSON error response.
2. Read committed state on a separate connection. Assert the ordered draft is still B at revision 2 and the ordered shared copy is still A.
3. Assert the report and current proposal records match the baseline snapshot.

See the [storage contract](../../developer/checklist-storage-contract.md), [checklist spec](../../../openspec/changes/share-investigation-checklist/specs/investigation-checklist/spec.md), and [backend verification notes](../../../src/backend/developer/README.md).
