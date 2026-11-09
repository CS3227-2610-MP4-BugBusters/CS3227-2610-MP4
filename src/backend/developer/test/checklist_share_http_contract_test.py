"""Contract checks for the local Share HTTP adapter (not production routing)."""

import json
import os
import subprocess
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit
from uuid import UUID


def snapshot(report_id, snapshot_sql):
    result = subprocess.run(
        ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-v", f"report_id={report_id}",
         "-f", snapshot_sql], check=True, capture_output=True, text=True, timeout=15,
    )
    return json.loads(result.stdout)


def request(connection_type, origin, path, headers, body):
    connection = connection_type(origin.hostname, origin.port, timeout=15)
    try:
        connection.request("POST", path, body=body,
                           headers={**headers, "Content-Type": "application/json"})
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def check_no_store(headers):
    cache_control = next((value for name, value in headers.items()
                          if name.lower() == "cache-control"), "")
    assert cache_control.lower() == "no-store", (
        f"Expected Cache-Control: no-store, got {cache_control!r}")


def check_error(response, status, code):
    actual_status, headers, raw = response
    check_no_store(headers)
    assert actual_status == status, f"Expected HTTP {status}, got {actual_status}: {raw!r}"
    body = json.loads(raw)
    assert body.get("code") == code, f"Expected {code}, got {body!r}"
    assert body.get("message") and body.get("requestId"), f"Malformed error body: {body!r}"


def main():
    required = ("MP4_TEST_ORIGIN", "MP4_TEST_SESSION_HEADERS", "MP4_TEST_DENIED_SESSION_HEADERS",
                "MP4_TEST_REPORT_ID", "MP4_TEST_DEVELOPER_ID", "MP4_TEST_SNAPSHOT_SQL", "PGDATABASE")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit("Setup blocked: missing " + ", ".join(missing))

    origin = urlsplit(os.environ["MP4_TEST_ORIGIN"])
    if (origin.scheme not in ("http", "https") or not origin.hostname or origin.username
            or origin.path not in ("", "/") or origin.query or origin.fragment):
        raise SystemExit("Setup blocked: MP4_TEST_ORIGIN must be an HTTP(S) origin")
    headers = json.loads(os.environ["MP4_TEST_SESSION_HEADERS"])
    denied_headers = json.loads(os.environ["MP4_TEST_DENIED_SESSION_HEADERS"])
    if not isinstance(headers, dict) or not headers or not isinstance(denied_headers, dict) or not denied_headers:
        raise SystemExit("Setup blocked: provide assigned and unassigned synthetic session headers")

    report_id = UUID(os.environ["MP4_TEST_REPORT_ID"])
    developer_id = str(UUID(os.environ["MP4_TEST_DEVELOPER_ID"]))
    snapshot_sql = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    path = f"/api/reports/{report_id}/checklist/share"
    state = snapshot(report_id, snapshot_sql)
    assert state["report"]["assigneeId"] == developer_id, "Fixture assignee differs"
    assert state["report"]["status"] in ("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED")
    before_version = state["report"]["version"]
    revision = state["draftRevision"]
    assert state["draftSteps"], "Success fixture must have a nonempty saved draft"
    assert state["draftSteps"] != state["sharedSteps"], "Success fixture must begin with changed steps"

    response = request(connection_type, origin, path, headers,
                       json.dumps({"expectedDraftRevision": revision,
                                   "expectedReportVersion": before_version}))
    status, response_headers, raw = response
    check_no_store(response_headers)
    assert status == 200, f"Expected HTTP 200, got {status}: {raw!r}"
    body = json.loads(raw)
    assert body == {"reportVersion": before_version + 1, "changed": True}, body
    published = snapshot(report_id, snapshot_sql)
    assert published["draftSteps"] == state["draftSteps"]
    assert published["sharedSteps"] == state["draftSteps"], "Share did not publish persisted draft"
    assert published["report"]["version"] == before_version + 1
    if state["report"]["status"] == "RESOLUTION_PROPOSED":
        assert published["report"]["status"] == "IN_PROGRESS"
        assert published["proposal"]["status"] == "SUPERSEDED"

    unchanged = request(connection_type, origin, path, headers,
                        json.dumps({"expectedDraftRevision": revision,
                                    "expectedReportVersion": before_version + 1}))
    status, response_headers, raw = unchanged
    check_no_store(response_headers)
    assert status == 200, f"Expected unchanged Share HTTP 200, got {status}: {raw!r}"
    assert json.loads(raw) == {"reportVersion": before_version + 1, "changed": False}
    assert snapshot(report_id, snapshot_sql) == published, "Unchanged Share mutated persisted state"

    stable = snapshot(report_id, snapshot_sql)
    check_error(request(connection_type, origin, path, headers,
                        json.dumps({"expectedDraftRevision": revision - 1,
                                    "expectedReportVersion": before_version + 1})),
                409, "STALE_VERSION")
    assert snapshot(report_id, snapshot_sql) == stable, "Stale Share mutated persisted state"
    check_error(request(connection_type, origin, path, headers,
                        json.dumps({"expectedDraftRevision": revision,
                                    "expectedReportVersion": before_version})),
                409, "STALE_VERSION")
    assert snapshot(report_id, snapshot_sql) == stable, "Stale report version mutated persisted state"

    check_error(request(connection_type, origin, path, denied_headers,
                        json.dumps({"expectedDraftRevision": revision,
                                    "expectedReportVersion": before_version + 1})),
                403, "FORBIDDEN")
    assert snapshot(report_id, snapshot_sql) == stable, "Denied Share mutated persisted state"

    subprocess.run(
        ["psql", "-Xq", "-v", "ON_ERROR_STOP=1", "-c",
         f"UPDATE reports SET status = 'SUBMITTED' WHERE id = '{report_id}'::uuid"],
        check=True, capture_output=True, text=True, timeout=15,
    )
    invalid_state = snapshot(report_id, snapshot_sql)
    check_error(request(connection_type, origin, path, headers,
                        json.dumps({"expectedDraftRevision": invalid_state["draftRevision"],
                                    "expectedReportVersion": invalid_state["report"]["version"]})),
                409, "INVALID_STATE")
    assert snapshot(report_id, snapshot_sql) == invalid_state, "Invalid-state Share mutated persisted state"
    subprocess.run(
        ["psql", "-Xq", "-v", "ON_ERROR_STOP=1", "-c",
         f"UPDATE reports SET status = 'IN_PROGRESS' WHERE id = '{report_id}'::uuid"],
        check=True, capture_output=True, text=True, timeout=15,
    )
    stable = snapshot(report_id, snapshot_sql)

    for invalid in (b"{", b"[]", b'{"expectedDraftRevision":2}',
                    b'{"expectedDraftRevision":-1,"expectedReportVersion":7}',
                    b'{"expectedDraftRevision":2,"expectedReportVersion":7,"steps":["client data"]}'):
        check_error(request(connection_type, origin, path, headers, invalid), 400, "INVALID_REQUEST")
        assert snapshot(report_id, snapshot_sql) == stable, "Invalid Share request mutated persisted state"

    subprocess.run(
        ["psql", "-Xq", "-v", "ON_ERROR_STOP=1", "-c",
         "UPDATE investigation_checklists SET draft_steps = '{}'::text[] "
         f"WHERE report_id = '{report_id}'::uuid"],
        check=True, capture_output=True, text=True, timeout=15,
    )
    empty_before = snapshot(report_id, snapshot_sql)
    assert empty_before["draftSteps"] == [], "Could not prepare empty-draft case"
    check_error(request(connection_type, origin, path, headers,
                        json.dumps({"expectedDraftRevision": empty_before["draftRevision"],
                                    "expectedReportVersion": empty_before["report"]["version"]})),
                409, "EMPTY_DRAFT")
    assert snapshot(report_id, snapshot_sql) == empty_before, "Empty Share mutated persisted state"

    print("Local Share HTTP changed/unchanged, stale, authorization, invalid-input and no-store checks passed")


if __name__ == "__main__":
    main()
