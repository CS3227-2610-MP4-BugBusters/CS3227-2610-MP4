"""Local HTTP contract for Reporter and Maintainer shared checklist reads."""

import json
import os
import subprocess
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit
from uuid import UUID


STEP_A = ["Investigate the first trace", "Compare initial timestamps"]
STEP_B = ["Reproduce with the new trace", "Record the corrected timestamps"]


def snapshot(report_id, snapshot_sql):
    result = subprocess.run(
        ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-v", f"report_id={report_id}",
         "-f", snapshot_sql], check=True, capture_output=True, text=True, timeout=15,
    )
    return json.loads(result.stdout)


def send(connection_type, origin, method, path, headers, body=None):
    connection = connection_type(origin.hostname, origin.port, timeout=15)
    try:
        request_headers = dict(headers)
        if body is not None:
            request_headers["Content-Type"] = "application/json"
        connection.request(method, path, body=body, headers=request_headers)
        response = connection.getresponse()
        return response.status, dict(response.getheaders()), response.read()
    finally:
        connection.close()


def check_no_store(headers):
    value = next((value for key, value in headers.items()
                  if key.lower() == "cache-control"), "")
    assert value.lower() == "no-store", f"Expected Cache-Control: no-store, got {value!r}"


def read_steps(connection_type, origin, path, headers, expected):
    status, response_headers, raw = send(connection_type, origin, "GET", path, headers)
    check_no_store(response_headers)
    assert status == 200, f"Expected shared read HTTP 200, got {status}: {raw!r}"
    body = json.loads(raw)
    assert body == {"steps": expected}, f"Shared response leaked or omitted fields: {body!r}"


def denied_read(connection_type, origin, path, headers):
    status, response_headers, raw = send(connection_type, origin, "GET", path, headers)
    check_no_store(response_headers)
    assert status == 403, f"Expected shared read HTTP 403, got {status}: {raw!r}"
    body = json.loads(raw)
    assert body.get("code") == "FORBIDDEN", f"Expected FORBIDDEN, got {body!r}"
    assert body.get("message") and body.get("requestId"), (
        f"Denied response missing shared error fields: {body!r}")
    assert "steps" not in body and "draftSteps" not in body and "draftRevision" not in body, (
        f"Denied response disclosed checklist data: {body!r}")
    return body


def main():
    required = ("MP4_TEST_ORIGIN", "MP4_TEST_SESSIONS", "MP4_TEST_REPORT_ID",
                "MP4_TEST_DEVELOPER_ID", "MP4_TEST_SNAPSHOT_SQL", "PGDATABASE")
    missing = [name for name in required if not os.environ.get(name)]
    if missing:
        raise SystemExit("Setup blocked: missing " + ", ".join(missing))
    origin = urlsplit(os.environ["MP4_TEST_ORIGIN"])
    if (origin.scheme not in ("http", "https") or not origin.hostname or origin.username
            or origin.path not in ("", "/") or origin.query or origin.fragment):
        raise SystemExit("Setup blocked: MP4_TEST_ORIGIN must be an HTTP(S) origin")
    sessions = json.loads(os.environ["MP4_TEST_SESSIONS"])
    required_sessions = {"developer", "reporter", "other_reporter", "maintainer",
                         "inactive_reporter", "inactive_maintainer", "unassigned_developer"}
    if not isinstance(sessions, dict) or not required_sessions.issubset(sessions):
        raise SystemExit("Setup blocked: MP4_TEST_SESSIONS must provide all seven synthetic sessions")
    if any(not isinstance(sessions[name], dict) or not sessions[name]
           for name in required_sessions):
        raise SystemExit("Setup blocked: every role session must be a nonempty HTTP header object")

    report_id = UUID(os.environ["MP4_TEST_REPORT_ID"])
    developer_id = str(UUID(os.environ["MP4_TEST_DEVELOPER_ID"]))
    snapshot_sql = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    read_path = f"/api/reports/{report_id}/checklist/shared"
    initial = snapshot(report_id, snapshot_sql)
    assert initial["report"]["assigneeId"] == developer_id
    assert initial["draftSteps"] == STEP_A and initial["sharedSteps"] == []
    assert initial["draftRevision"] == 2 and initial["report"]["version"] == 7

    # The local runner grants view access only to this synthetic Maintainer actor.
    # Production per-report Maintainer permission integration remains unresolved.
    read_steps(connection_type, origin, read_path, sessions["reporter"], [])
    read_steps(connection_type, origin, read_path, sessions["maintainer"], [])
    missing_report_denial = denied_read(
        connection_type, origin,
        "/api/reports/00000000-0000-0000-0000-000000000499/checklist/shared",
        sessions["reporter"])
    other_reporter_denial = denied_read(connection_type, origin, read_path, sessions["other_reporter"])
    assert (missing_report_denial["code"], missing_report_denial["message"]) == (
        other_reporter_denial["code"], other_reporter_denial["message"]), (
        "Missing and unauthorized reports disclose different error details")
    denied_read(connection_type, origin, read_path, sessions["inactive_reporter"])
    denied_read(connection_type, origin, read_path, sessions["inactive_maintainer"])
    denied_read(connection_type, origin, read_path, sessions["unassigned_developer"])

    share = send(connection_type, origin, "POST", read_path.replace("/shared", "/share"),
                 sessions["developer"], json.dumps({"expectedDraftRevision": 2,
                                                     "expectedReportVersion": 7}))
    status, headers, raw = share
    check_no_store(headers)
    assert status == 200 and json.loads(raw) == {"reportVersion": 8, "changed": True}, (
        f"Share A failed: HTTP {status}: {raw!r}")
    after_a = snapshot(report_id, snapshot_sql)
    assert after_a["sharedSteps"] == STEP_A and after_a["report"]["version"] == 8

    save = send(connection_type, origin, "PUT", read_path.replace("/shared", "/draft"),
                sessions["developer"], json.dumps({"steps": STEP_B, "expectedDraftRevision": 2}))
    status, headers, raw = save
    assert status == 200 and json.loads(raw) == {"draftRevision": 3}, (
        f"Private Save B failed: HTTP {status}: {raw!r}")
    after_private_save = snapshot(report_id, snapshot_sql)
    assert after_private_save["draftSteps"] == STEP_B
    assert after_private_save["sharedSteps"] == STEP_A
    assert after_private_save["report"]["version"] == 8
    read_steps(connection_type, origin, read_path, sessions["reporter"], STEP_A)
    read_steps(connection_type, origin, read_path, sessions["maintainer"], STEP_A)

    share = send(connection_type, origin, "POST", read_path.replace("/shared", "/share"),
                 sessions["developer"], json.dumps({"expectedDraftRevision": 3,
                                                     "expectedReportVersion": 8}))
    status, headers, raw = share
    check_no_store(headers)
    assert status == 200 and json.loads(raw) == {"reportVersion": 9, "changed": True}, (
        f"Share B failed: HTTP {status}: {raw!r}")
    after_b = snapshot(report_id, snapshot_sql)
    assert after_b["sharedSteps"] == STEP_B
    read_steps(connection_type, origin, read_path, sessions["reporter"], STEP_B)
    read_steps(connection_type, origin, read_path, sessions["maintainer"], STEP_B)

    save_empty = send(connection_type, origin, "PUT", read_path.replace("/shared", "/draft"),
                      sessions["developer"], json.dumps({"steps": [], "expectedDraftRevision": 3}))
    status, headers, raw = save_empty
    assert status == 200 and json.loads(raw) == {"draftRevision": 4}, (
        f"Empty private Save failed: HTTP {status}: {raw!r}")
    empty_before = snapshot(report_id, snapshot_sql)
    assert empty_before["draftSteps"] == [] and empty_before["sharedSteps"] == STEP_B
    empty_share = send(connection_type, origin, "POST", read_path.replace("/shared", "/share"),
                       sessions["developer"], json.dumps({"expectedDraftRevision": 4,
                                                           "expectedReportVersion": 9}))
    status, headers, raw = empty_share
    check_no_store(headers)
    assert status == 409 and json.loads(raw).get("code") == "EMPTY_DRAFT", (
        f"Expected empty Share 409 EMPTY_DRAFT, got {status}: {raw!r}")
    assert snapshot(report_id, snapshot_sql) == empty_before, "Rejected empty Share mutated state"
    read_steps(connection_type, origin, read_path, sessions["reporter"], STEP_B)
    read_steps(connection_type, origin, read_path, sessions["maintainer"], STEP_B)

    print("Shared checklist reads passed: pre-share projection, private-save isolation, post-share updates, empty rejection, privacy, authorization, and no-store")


if __name__ == "__main__":
    main()
