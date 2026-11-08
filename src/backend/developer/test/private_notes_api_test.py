"""Exercise private-notes defaults, versioning, and proposal preservation."""

import json
import os
import subprocess
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit
from uuid import UUID


PATH = "/api/reports/{report_id}/private-notes"
TEXT = "Checked the stack trace"


def snapshot(report_id, snapshot_sql):
    result = subprocess.run(
        ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-v", f"report_id={report_id}",
         "-f", snapshot_sql],
        check=True, capture_output=True, text=True, timeout=15,
    )
    return json.loads(result.stdout)


def request(origin, headers, method, path, payload=None):
    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    connection = connection_type(origin.hostname, origin.port, timeout=15)
    try:
        request_headers = dict(headers)
        body = None
        if payload is not None:
            body = json.dumps(payload)
            request_headers["Content-Type"] = "application/json"
        connection.request(method, path, body=body, headers=request_headers)
        response = connection.getresponse()
        raw = response.read()
        return response.status, json.loads(raw) if raw else None, response.getheaders()
    finally:
        connection.close()


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

    report_id = str(UUID(os.environ["MP4_TEST_REPORT_ID"]))
    developer_id = str(UUID(os.environ["MP4_TEST_DEVELOPER_ID"]))
    snapshot_sql = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    before = snapshot(report_id, snapshot_sql)
    assert before["notes"] == {"text": "", "revision": 0}, "Fixture must start without a notes row"
    assert before["report"]["assigneeId"] == developer_id, "Fixture assignee differs"
    assert before["report"]["status"] == "RESOLUTION_PROPOSED", "Fixture must have a pending proposal"
    assert before["report"]["version"] == 7, "Fixture report version must be 7"
    assert before["proposal"]["status"] == "PENDING", "Fixture proposal must be pending"

    path = PATH.format(report_id=report_id)
    session_status, session_body, _ = request(origin, headers, "GET", "/__test/session")
    assert session_status == 200, f"Synthetic session control failed: {session_status} {session_body}"
    assert session_body == {"id": developer_id, "role": "DEVELOPER", "active": True}, (
        f"Synthetic session actor differs from fixture assignee: {session_body}"
    )
    status, body, response_headers = request(origin, headers, "GET", path)
    if status == 404:
        raise SystemExit(
            "Baseline blocked: synthetic session control is reachable, but the private-notes "
            "route is not mounted (HTTP 404); no notes behavior was exercised."
        )
    assert status == 200, f"Expected default read HTTP 200, got {status}: {body}"
    assert body == {"text": "", "notesRevision": 0}, f"Unexpected default response: {body}"
    assert any(name.lower() == "cache-control" and value.lower() == "no-store"
               for name, value in response_headers), "Expected no-store response"

    status, body, _ = request(origin, headers, "PUT", path,
                              {"text": TEXT, "expectedNotesRevision": 0})
    assert status == 200 and body == {"notesRevision": 1}, f"Save failed: {status} {body}"
    status, body, _ = request(origin, headers, "GET", path)
    assert status == 200 and body == {"text": TEXT, "notesRevision": 1}, (
        f"Saved note readback failed: {status} {body}"
    )
    after_save = snapshot(report_id, snapshot_sql)
    assert after_save["notes"] == {"text": TEXT, "revision": 1}, "Saved note state differs"
    assert after_save["report"] == before["report"], "Private save changed report state/version"
    assert after_save["proposal"] == before["proposal"], "Private save changed pending proposal"

    status, body, _ = request(origin, headers, "PUT", path,
                              {"text": "", "expectedNotesRevision": 1})
    assert status == 200 and body == {"notesRevision": 2}, f"Clear failed: {status} {body}"
    status, body, _ = request(origin, headers, "GET", path)
    assert status == 200 and body == {"text": "", "notesRevision": 2}, (
        f"Cleared note readback failed: {status} {body}"
    )
    after_clear = snapshot(report_id, snapshot_sql)
    assert after_clear["notes"] == {"text": "", "revision": 2}, "Clear reset or lost the revision"
    assert after_clear["report"] == before["report"], "Private clear changed report state/version"
    assert after_clear["proposal"] == before["proposal"], "Private clear changed pending proposal"

    status, body, _ = request(origin, headers, "PUT", path,
                              {"text": "stale restore", "expectedNotesRevision": 1})
    assert status == 409 and body.get("code") == "STALE_VERSION", (
        f"Expected stale conflict, got {status}: {body}"
    )
    assert snapshot(report_id, snapshot_sql) == after_clear, "Stale write changed committed state"
    print("Private-notes default, save, clear, stale rejection, and proposal preservation passed")


if __name__ == "__main__":
    main()
