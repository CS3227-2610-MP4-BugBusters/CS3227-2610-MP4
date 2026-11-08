"""Pagination and filtered-count contract tests against the application HTTP handler."""

import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request


R301 = "00000000-0000-0000-0000-000000000301"
R302 = "00000000-0000-0000-0000-000000000302"
R303 = "00000000-0000-0000-0000-000000000303"


def request(origin, query="", token="d1-token"):
    path = "/api/developer/reports"
    if query:
        path += "?" + query
    headers = {"Accept": "application/json"}
    if token is not None:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(origin.rstrip("/") + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def result(origin, query="", token="d1-token"):
    status, body = request(origin, query, token)
    assert status == 200, f"expected HTTP 200, got {status}: {body!r}"
    return json.loads(body)


def rejected(origin, query):
    status, body = request(origin, query)
    assert status == 400, f"expected HTTP 400 for {query!r}, got {status}: {body!r}"
    error = json.loads(body)
    assert error["code"] == "VALIDATION_FAILED"
    assert error["message"] and error["requestId"]


def main():
    origin = os.environ["MP4_TEST_ORIGIN"]
    database_uri = os.environ["MP4_TEST_DATABASE_URI"]
    parsed = urllib.parse.urlsplit(database_uri)
    assert os.environ.get("MP4_TEST_DATABASE_NAME") == "mp4_assigned_reports_test"
    assert parsed.path.lstrip("/") == "mp4_assigned_reports_test", \
        "pagination checks require the disposable assigned-reports database"
    fixture = os.path.join(os.path.dirname(__file__), "assigned-reports-fixture.sql")
    reset = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1", "-f", fixture],
                           text=True, capture_output=True)
    assert reset.returncode == 0, "disposable fixture setup failed: " + reset.stderr

    default = result(origin)
    assert default == {"items": [
        {"id": R301, "title": "Login fails", "status": "ASSIGNED"},
        {"id": R302, "title": "Closed report", "status": "CLOSED"}],
        "page": 1, "pageSize": 20, "totalItems": 2}

    first = result(origin, "page=1&pageSize=1")
    second = result(origin, "page=2&pageSize=1")
    assert [row["id"] for row in first["items"]] == [R301]
    assert [row["id"] for row in second["items"]] == [R302]
    assert (first["page"], first["pageSize"], first["totalItems"]) == (1, 1, 2)
    assert (second["page"], second["pageSize"], second["totalItems"]) == (2, 1, 2)

    max_size = result(origin, "pageSize=100")
    assert (max_size["page"], max_size["pageSize"], max_size["totalItems"]) == (1, 100, 2)
    beyond = result(origin, "page=3&pageSize=1")
    assert beyond == {"items": [], "page": 3, "pageSize": 1, "totalItems": 2}

    filtered = result(origin, "search=login&status=ASSIGNED&page=2&pageSize=1")
    assert filtered == {"items": [], "page": 2, "pageSize": 1, "totalItems": 1}
    # A matching report assigned to another developer must not affect D1's page or total.
    assert result(origin, "search=login&status=IN_PROGRESS")["totalItems"] == 0
    other_actor = result(origin, "search=login&status=IN_PROGRESS", "d2-token")
    assert [row["id"] for row in other_actor["items"]] == [R303]
    assert other_actor["totalItems"] == 1
    assert result(origin, token="empty-token")["totalItems"] == 0

    assert request(origin, token=None)[0] == 401
    status, body = request(origin, token="reporter-token")
    assert status == 403 and json.loads(body)["code"] == "FORBIDDEN"

    for query in (
        "page=0", "page=-1", "page=1.2", "page=abc", "page=",
        "page=1&page=2", "page=2147483648", "page=999999999999999999999999",
        "pageSize=0", "pageSize=-1", "pageSize=1.2", "pageSize=abc", "pageSize=",
        "pageSize=101", "pageSize=2147483648", "pageSize=1&pageSize=2",
    ):
        rejected(origin, query)

    print("Assigned-report pagination API checks passed")


if __name__ == "__main__":
    main()
