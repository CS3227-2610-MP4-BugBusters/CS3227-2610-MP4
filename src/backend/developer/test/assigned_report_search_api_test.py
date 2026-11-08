"""Search contract checks against the application handler and synthetic sessions.

Requires a disposable PostgreSQL database named mp4_assigned_reports_test and
the origin printed by AssignedReportListHttpHarness. This suite resets only
that database through the dedicated assigned-reports fixture.
"""

import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request


D1 = "00000000-0000-0000-0000-000000000401"
D2 = "00000000-0000-0000-0000-000000000402"
R301 = "00000000-0000-0000-0000-000000000301"
R302 = "00000000-0000-0000-0000-000000000302"
R303 = "00000000-0000-0000-0000-000000000303"


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


def result(origin, params="", token="d1-token"):
    status, body = request(origin, params, token)
    assert status == 200, f"expected HTTP 200, got {status}: {body!r}"
    return json.loads(body)


def report_ids(payload):
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

    # Setup controls prove the real handler, actor adapter, and fixture are live.
    anonymous = request(origin, token=None)
    assert anonymous[0] == 401 and json.loads(anonymous[1])["code"] == "UNAUTHENTICATED"
    baseline = result(origin)
    assert report_ids(baseline) == [R301, R302] and baseline["totalItems"] == 2

    # Case-insensitive title substring; complete ID is matched case-insensitively.
    assert report_ids(result(origin, "search=%20lOgIn%20")) == [R301]
    assert report_ids(result(origin, "search=" + R302.upper())) == [R302]
    assert result(origin, "search=%20%20%20")["totalItems"] == 2

    # LIKE metacharacters and backslash are literal search text.
    for literal in ("%", "_", "\\"):
        assert result(origin, "search=" + urllib.parse.quote(literal, safe=""))["totalItems"] == 0

    # Authorization applies before search and totals: D1 cannot see D2's match.
    assert result(origin, "search=another%20developer")["items"] == []
    assert result(origin, "search=another%20developer")["totalItems"] == 0
    forged = request(origin, "developerId=" + D2)
    assert forged[0] == 400 and json.loads(forged[1])["code"] == "VALIDATION_FAILED"

    repeated = request(origin, "search=login&search=closed")
    assert repeated[0] == 400 and json.loads(repeated[1])["code"] == "VALIDATION_FAILED"

    # Reassignment takes effect on the next request for both session actors.
    sql = "UPDATE reports SET assignee_id = '" + D2 + "' WHERE id = '" + R301 + "';\n"
    changed = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1"], input=sql,
                             text=True, capture_output=True)
    assert changed.returncode == 0, "reassignment failed: " + changed.stderr
    assert R301 not in report_ids(result(origin))
    assert R301 in report_ids(result(origin, token="d2-token"))
    print("Assigned-report search contract checks passed")


if __name__ == "__main__":
    main()
