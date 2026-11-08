"""Black-box assigned-report contract tests against an application HTTP origin.

The origin must be the real application route with its test-only session adapter,
not a reference implementation. A disposable database is required because the
reassignment case mutates the report assignment.
"""

import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request


D1 = "00000000-0000-0000-0000-000000000401"
D2 = "00000000-0000-0000-0000-000000000402"
REPORTER = "00000000-0000-0000-0000-000000000403"
R301 = "00000000-0000-0000-0000-000000000301"
R302 = "00000000-0000-0000-0000-000000000302"
R303 = "00000000-0000-0000-0000-000000000303"
R304 = "00000000-0000-0000-0000-000000000304"


def request(origin, path, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(origin.rstrip("/") + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def payload(status, body, expected):
    assert status == expected, f"expected HTTP {expected}, got {status}: {body!r}"
    return json.loads(body) if body else None


def ids(response):
    return [item["id"] for item in response["items"]]


def main():
    origin = os.environ["MP4_TEST_ORIGIN"]
    tokens = {
        "d1": os.environ["MP4_TEST_D1_TOKEN"],
        "d2": os.environ["MP4_TEST_D2_TOKEN"],
        "reporter": os.environ["MP4_TEST_REPORTER_TOKEN"],
        "inactive": os.environ["MP4_TEST_INACTIVE_TOKEN"],
    }
    database_uri = os.environ["MP4_TEST_DATABASE_URI"]
    parsed_database = urllib.parse.urlsplit(database_uri)
    if os.environ.get("MP4_TEST_DATABASE_NAME") != "mp4_assigned_reports_test" or \
            parsed_database.path.lstrip("/") != "mp4_assigned_reports_test":
        raise SystemExit("Setup blocked: use a disposable database named mp4_assigned_reports_test")
    fixture = os.path.join(os.path.dirname(__file__), "assigned-reports-fixture.sql")
    reset = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1", "-f", fixture],
                           text=True, capture_output=True)
    assert reset.returncode == 0, "disposable fixture setup failed: " + reset.stderr

    route = "/api/developer/reports"
    # Authentication and assignment isolation, including closed assigned rows.
    assert request(origin, route)[0] == 401
    for role_token in (tokens["reporter"], tokens["inactive"]):
        status, body = request(origin, route, role_token)
        error = payload(status, body, 403)
        assert error["code"] == "FORBIDDEN" and error["requestId"] and not error.get("items")

    d1 = payload(*request(origin, route, tokens["d1"]), 200)
    assert ids(d1) == [R301, R302]
    assert d1 == {"items": [
        {"id": R301, "title": "Login fails", "status": "ASSIGNED"},
        {"id": R302, "title": "Closed report", "status": "CLOSED"}],
        "page": 1, "pageSize": 20, "totalItems": 2}
    assert all(set(row) == {"id", "title", "status"} for row in d1["items"])
    d2 = payload(*request(origin, route, tokens["d2"]), 200)
    assert ids(d2) == [R303]
    ignored_identity = payload(*request(origin, route + "?developerId=" + D2, tokens["d1"]), 400)
    assert ignored_identity["code"] == "VALIDATION_FAILED"

    # Literal title and complete-ID search, status AND search, and whitespace.
    assert ids(payload(*request(origin, route + "?search=%20LOGIN%20", tokens["d1"]), 200)) == [R301]
    assert ids(payload(*request(origin, route + "?search=" + R302, tokens["d1"]), 200)) == [R302]
    combined = payload(*request(origin, route + "?search=login&status=CLOSED", tokens["d1"]), 200)
    assert combined["items"] == [] and combined["totalItems"] == 0
    assert payload(*request(origin, route + "?search=%20%20", tokens["d1"]), 200)["totalItems"] == 2
    # Wildcards are literal characters, not SQL LIKE operators.
    assert payload(*request(origin, route + "?search=%25", tokens["d1"]), 200)["totalItems"] == 0

    # Deterministic ordering, defaults, filtered totals, and out-of-range page.
    page = payload(*request(origin, route + "?page=2&pageSize=1", tokens["d1"]), 200)
    assert page["items"][0]["id"] == R302 and (page["page"], page["pageSize"], page["totalItems"]) == (2, 1, 2)
    beyond = payload(*request(origin, route + "?page=8&pageSize=1", tokens["d1"]), 200)
    assert beyond["items"] == [] and beyond["totalItems"] == 2

    for query in ("page=0", "page=-1", "page=1.2", "page=1&page=2", "pageSize=101",
                  "status=assigned", "status=UNKNOWN", "unknown=x", "search=a&search=b"):
        error = payload(*request(origin, route + "?" + query, tokens["d1"]), 400)
        assert error["code"] == "VALIDATION_FAILED" and error["message"] and error["requestId"]

    # Reassignment is observed on each subsequent request by both trusted actors.
    sql = ("UPDATE reports SET assignee_id = '" + D2 + "' WHERE id = '" + R301 + "';\n")
    changed = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1"], input=sql,
                             text=True, capture_output=True)
    assert changed.returncode == 0, "reassignment failed: " + changed.stderr
    assert R301 not in ids(payload(*request(origin, route, tokens["d1"]), 200))
    assert R301 in ids(payload(*request(origin, route, tokens["d2"]), 200))
    print("Assigned-report application API contract checks passed")


if __name__ == "__main__":
    main()
