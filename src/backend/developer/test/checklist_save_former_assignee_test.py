"""Former assignees cannot overwrite a checklist after reassignment."""

import json
import os
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit
from uuid import UUID

from checklist_save_api_test import snapshot, verify_fixture


SAVED_STEPS = ["Reproduce the bug", "Capture a new trace"]


def main():
    required = ("MP4_TEST_ORIGIN", "MP4_TEST_SESSION_HEADERS", "MP4_TEST_REPORT_ID",
                "MP4_TEST_DEVELOPER_ID", "MP4_TEST_SNAPSHOT_SQL", "PGDATABASE",
                "MP4_TEST_NEW_ASSIGNEE_ID")
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
        raise SystemExit("Setup blocked: provide D1's authenticated session headers")

    report_id = UUID(os.environ["MP4_TEST_REPORT_ID"])
    former_assignee_id = str(UUID(os.environ["MP4_TEST_DEVELOPER_ID"]))
    new_assignee_id = str(UUID(os.environ["MP4_TEST_NEW_ASSIGNEE_ID"]))
    if former_assignee_id == new_assignee_id:
        raise SystemExit("Setup blocked: D1 and D2 must be distinct Developers")

    snapshot_sql = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    before = snapshot(report_id, snapshot_sql)
    verify_fixture(before, new_assignee_id)
    assert before["report"]["assigneeId"] != former_assignee_id, (
        "Fixture must be reassigned away from the authenticated former assignee"
    )
    if before["draftSteps"] == before["sharedSteps"]:
        raise AssertionError("Fixture draft and shared copies must be distinct")

    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    path = f"/api/reports/{report_id}/checklist/draft"
    connection = connection_type(origin.hostname, origin.port, timeout=15)
    try:
        connection.request(
            "PUT", path,
            body=json.dumps({"steps": SAVED_STEPS, "expectedDraftRevision": 2}),
            headers={**headers, "Content-Type": "application/json"},
        )
        response = connection.getresponse()
        payload = json.load(response)
        assert response.status == 403, f"Expected HTTP 403, got {response.status}"
        assert payload.get("code") == "FORBIDDEN", "Expected FORBIDDEN error code"
    finally:
        connection.close()

    after = snapshot(report_id, snapshot_sql)
    assert after == before, "Denied former-assignee request changed committed report/checklist/proposal state"
    print("Former-assignee Save was denied and committed state was preserved")


if __name__ == "__main__":
    main()
