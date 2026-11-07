"""Reject a stale private Save without changing committed state."""

import json
import os
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit
from uuid import UUID

from checklist_save_api_test import snapshot, verify_fixture


SAVED_STEPS = ["Reproduce the bug", "Capture a new trace"]


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
    before = snapshot(report_id, os.environ["MP4_TEST_SNAPSHOT_SQL"])
    verify_fixture(before, developer_id)

    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    connection = connection_type(origin.hostname, origin.port, timeout=15)
    try:
        connection.request(
            "PUT", f"/api/reports/{report_id}/checklist/draft",
            body=json.dumps({"steps": SAVED_STEPS, "expectedDraftRevision": 1}),
            headers={**headers, "Content-Type": "application/json"},
        )
        response = connection.getresponse()
        body = json.load(response)
        assert response.status == 409, f"Expected HTTP 409, got {response.status}: {body}"
        assert body.get("code") == "STALE_VERSION", f"Expected STALE_VERSION, got {body}"
        assert isinstance(body.get("message"), str) and body["message"], (
            f"Expected an error message, got {body}"
        )
        assert isinstance(body.get("requestId"), str) and body["requestId"], (
            f"Expected a requestId, got {body}"
        )
    finally:
        connection.close()

    after = snapshot(report_id, os.environ["MP4_TEST_SNAPSHOT_SQL"])
    assert after == before, "Stale Save changed draft, shared steps, report, or proposal"
    print("Stale private Save passed: 409 STALE_VERSION and committed state unchanged")


if __name__ == "__main__":
    main()
