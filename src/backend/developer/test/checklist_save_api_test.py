"""Exercise private Save through the real authenticated application."""

import json
import os
import subprocess
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit
from uuid import UUID


SHARED_STEPS = ["Read the incident report", "Check the logs"]
DRAFT_STEPS = ["Compare timestamps", "Review the first trace"]
SAVED_STEPS = ["Reproduce the bug", "Capture a new trace"]


def snapshot(report_id, snapshot_sql):
    result = subprocess.run(
        ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1",
         "-v", f"report_id={report_id}", "-f", snapshot_sql],
        check=True, capture_output=True, text=True, timeout=15,
    )
    return json.loads(result.stdout)


def verify_fixture(state, developer_id):
    assert state["draftSteps"] == DRAFT_STEPS, "Fixture draft steps differ"
    assert state["draftRevision"] == 2, "Fixture draft revision must be 2"
    assert state["sharedSteps"] == SHARED_STEPS, "Fixture shared steps differ"
    report = state["report"]
    assert report["assigneeId"] == developer_id, "Fixture assignee differs"
    assert report["status"] == "RESOLUTION_PROPOSED", "Fixture report status differs"
    assert report["version"] == 7, "Fixture report version must be 7"
    proposal = state["proposal"]
    assert proposal["id"] and report["currentProposalId"] == proposal["id"], (
        "Fixture report must reference the snapshotted proposal"
    )
    assert proposal["status"] == "PENDING", "Fixture proposal must be pending"
    assert proposal["version"] is not None, "Fixture proposal must be versioned"
    assert proposal["contents"], "Fixture proposal must have known contents"


def verify_saved_state(before, after):
    assert after["draftSteps"] == SAVED_STEPS, "Save did not persist steps in order"
    assert after["draftRevision"] == 3, "Save did not increment the stored draft revision"
    assert after["sharedSteps"] == before["sharedSteps"], "Save changed the shared copy"
    assert after["report"] == before["report"], "Save changed the report"
    assert after["proposal"] == before["proposal"], "Save changed the pending proposal"


def main():
    required = ("MP4_TEST_ORIGIN", "MP4_TEST_SESSION_HEADERS", "MP4_TEST_REPORT_ID",
                "MP4_TEST_DEVELOPER_ID", "MP4_TEST_SNAPSHOT_SQL", "PGDATABASE")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit("Setup blocked: missing " + ", ".join(missing))

    origin = urlsplit(os.environ["MP4_TEST_ORIGIN"])
    if (origin.scheme not in ("http", "https") or not origin.hostname
            or origin.username or origin.path not in ("", "/")
            or origin.query or origin.fragment):
        raise SystemExit("Setup blocked: MP4_TEST_ORIGIN must be an HTTP(S) origin")

    headers = json.loads(os.environ["MP4_TEST_SESSION_HEADERS"])
    if not isinstance(headers, dict) or not headers:
        raise SystemExit("Setup blocked: provide the authenticated session headers")

    report_id = UUID(os.environ["MP4_TEST_REPORT_ID"])
    developer_id = str(UUID(os.environ["MP4_TEST_DEVELOPER_ID"]))
    snapshot_sql = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    before = snapshot(report_id, snapshot_sql)
    verify_fixture(before, developer_id)

    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    connection = connection_type(origin.hostname, origin.port, timeout=15)
    try:
        connection.request(
            "PUT", f"/api/reports/{report_id}/checklist/draft",
            body=json.dumps({"steps": SAVED_STEPS, "expectedDraftRevision": 2}),
            headers={**headers, "Content-Type": "application/json"},
        )
        response = connection.getresponse()
        assert response.status == 200, f"Expected HTTP 200, got {response.status}"
        body = json.load(response)
        assert body == {"draftRevision": 3}, "Expected response draft revision 3"
    finally:
        connection.close()

    verify_saved_state(before, snapshot(report_id, snapshot_sql))
    print("Authenticated private Save passed")


if __name__ == "__main__":
    main()
