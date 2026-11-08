"""Status-filter behavior checks against the application handler and synthetic sessions."""

import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request


D1 = "00000000-0000-0000-0000-000000000401"
D2 = "00000000-0000-0000-0000-000000000402"
STATUS_IDS = {
    "SUBMITTED": "00000000-0000-0000-0000-000000000411",
    "ASSIGNED": "00000000-0000-0000-0000-000000000412",
    "IN_PROGRESS": "00000000-0000-0000-0000-000000000413",
    "RESOLUTION_PROPOSED": "00000000-0000-0000-0000-000000000414",
    "CLOSED": "00000000-0000-0000-0000-000000000415",
}
OTHER_MATCH = "00000000-0000-0000-0000-000000000416"


def request(origin, query="", token="d1-token"):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    path = "/api/developer/reports" + ("?" + query if query else "")
    req = urllib.request.Request(origin.rstrip("/") + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def success(origin, query="", token="d1-token"):
    status, body = request(origin, query, token)
    assert status == 200, f"expected HTTP 200, got {status}: {body!r}"
    return json.loads(body)


def error_code(response, status, code):
    actual_status, body = response
    assert actual_status == status and json.loads(body)["code"] == code, (actual_status, body)


def ids(payload):
    return [item["id"] for item in payload["items"]]


def main():
    origin = os.environ["MP4_TEST_ORIGIN"]
    database_uri = os.environ["MP4_TEST_DATABASE_URI"]
    parsed = urllib.parse.urlsplit(database_uri)
    if (os.environ.get("MP4_TEST_DATABASE_NAME") != "mp4_assigned_reports_test"
            or parsed.path.lstrip("/") != "mp4_assigned_reports_test"):
        raise SystemExit("Setup blocked: use a disposable database named mp4_assigned_reports_test")

    fixture = os.path.join(os.path.dirname(__file__), "assigned-reports-fixture.sql")
    reset = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1", "-f", fixture],
                           text=True, capture_output=True)
    assert reset.returncode == 0, "disposable fixture setup failed: " + reset.stderr
    rows = [
        f"('{report_id}', 'Status filter target', '{D1}', '{status}', '{D1}')"
        for status, report_id in STATUS_IDS.items()
    ]
    rows.append(f"('{OTHER_MATCH}', 'Status filter target', '{D2}', 'ASSIGNED', '{D2}')")
    inserted = subprocess.run(
        ["psql", database_uri, "-v", "ON_ERROR_STOP=1"],
        input="INSERT INTO reports (id, title, assignee_id, status, reporter_id) VALUES\n"
              + ",\n".join(rows) + ";\n",
        text=True, capture_output=True)
    assert inserted.returncode == 0, "status fixture setup failed: " + inserted.stderr

    # Setup/auth controls establish real handler and fixture behavior.
    error_code(request(origin, token=None), 401, "UNAUTHENTICATED")
    error_code(request(origin, token="reporter-token"), 403, "FORBIDDEN")
    all_rows = success(origin, "search=Status%20filter%20target")
    assert all_rows["totalItems"] == len(STATUS_IDS)
    assert set(ids(all_rows)) == set(STATUS_IDS.values())
    assert OTHER_MATCH not in ids(all_rows)

    # Every wire status is accepted and filters only D1's current assignment.
    for status, report_id in STATUS_IDS.items():
        payload = success(origin, "search=Status%20filter%20target&status=" + status)
        assert ids(payload) == [report_id], (status, payload)
        assert payload["totalItems"] == 1
        assert payload["items"][0]["status"] == status

    # Search and status are combined with AND; totals exclude other developers.
    no_match = success(origin, "search=Status%20filter%20target&status=CLOSED")
    assert ids(no_match) == [STATUS_IDS["CLOSED"]] and no_match["totalItems"] == 1
    no_title_match = success(origin, "search=absent&status=CLOSED")
    assert no_title_match["items"] == [] and no_title_match["totalItems"] == 0
    assert OTHER_MATCH not in ids(success(origin, "status=ASSIGNED"))

    # Percent-encoded parameter names decode before validation and duplicate checks.
    assert ids(success(origin, "st%61tus=IN_PROGRESS")) == [STATUS_IDS["IN_PROGRESS"]]

    # Unknown, invalid, repeated, and encoded invalid status values are rejected.
    for query in ("status=closed", "status=UNKNOWN", "status=", "status=ASSIGNED&status=CLOSED",
                  "status=ASSIGNED&st%61tus=CLOSED", "status=SUBMITTED%26status%3DCLOSED"):
        error_code(request(origin, query), 400, "VALIDATION_FAILED")

    # Assignment is evaluated again after a report changes assignee.
    sql = "UPDATE reports SET assignee_id = '" + D2 + "' WHERE id = '" + STATUS_IDS["CLOSED"] + "';\n"
    changed = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1"], input=sql,
                             text=True, capture_output=True)
    assert changed.returncode == 0, "reassignment failed: " + changed.stderr
    assert ids(success(origin, "status=CLOSED")) == ["00000000-0000-0000-0000-000000000302"]
    assert STATUS_IDS["CLOSED"] in ids(success(origin, "status=CLOSED", token="d2-token"))
    print("Assigned-report status-filter contract checks passed")


if __name__ == "__main__":
    main()
