"""Security and literal-matching regressions for assigned-report search."""

import json
import os
import subprocess
import urllib.error
import urllib.parse
import urllib.request


D1 = "00000000-0000-0000-0000-000000000401"
R_PERCENT = "00000000-0000-0000-0000-000000000305"
R_UNDERSCORE = "00000000-0000-0000-0000-000000000306"
R_BACKSLASH = "00000000-0000-0000-0000-000000000307"
R_UUID = "0000000a-0000-0000-0000-000000000308"
R_ACCENT = "00000000-0000-0000-0000-000000000309"
R_SHARP_S = "00000000-0000-0000-0000-000000000310"


def request(origin, query):
    url = origin.rstrip("/") + "/api/developer/reports?" + query
    req = urllib.request.Request(url, headers={"Authorization": "Bearer d1-token"})
    try:
        with urllib.request.urlopen(req, timeout=10) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


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
    rows = f"""
INSERT INTO reports (id, title, assignee_id, status, reporter_id) VALUES
('{R_PERCENT}', 'Literal % marker', '{D1}', 'ASSIGNED', '{D1}'),
('{R_UNDERSCORE}', 'Literal _ marker', '{D1}', 'ASSIGNED', '{D1}'),
('{R_BACKSLASH}', 'Literal \\ marker', '{D1}', 'ASSIGNED', '{D1}'),
('{R_UUID}', 'Mixed case UUID row', '{D1}', 'ASSIGNED', '{D1}'),
('{R_ACCENT}', 'Échec de connexion', '{D1}', 'ASSIGNED', '{D1}'),
('{R_SHARP_S}', 'Fußball report', '{D1}', 'ASSIGNED', '{D1}');
"""
    inserted = subprocess.run(["psql", database_uri, "-v", "ON_ERROR_STOP=1"], input=rows,
                              text=True, capture_output=True)
    assert inserted.returncode == 0, "security fixture setup failed: " + inserted.stderr

    for malformed in ("%FF", "%C0%AF"):
        status, body = request(origin, "search=" + malformed)
        assert status == 400 and json.loads(body)["code"] == "VALIDATION_FAILED", (status, body)
    for nul in ("%00", "lo%00gin"):
        status, body = request(origin, "search=" + nul)
        assert status == 400 and json.loads(body)["code"] == "VALIDATION_FAILED", (status, body)

    for literal, expected in (("%", R_PERCENT), ("_", R_UNDERSCORE), ("\\", R_BACKSLASH)):
        status, body = request(origin, "search=" + urllib.parse.quote(literal, safe=""))
        assert status == 200, (status, body)
        result = json.loads(body)
        assert [item["id"] for item in result["items"]] == [expected], result

    status, body = request(origin, "search=" + urllib.parse.quote("\u2003", safe=""))
    assert status == 200 and json.loads(body)["totalItems"] == 8, (status, body)
    status, body = request(origin, "search=%E2%80%83login%E2%80%83")
    assert status == 200 and [item["id"] for item in json.loads(body)["items"]] == [
        "00000000-0000-0000-0000-000000000301"
    ], (status, body)

    status, body = request(origin, "search=" + R_UUID.upper())
    assert status == 200 and [item["id"] for item in json.loads(body)["items"]] == [R_UUID], (status, body)
    status, body = request(origin, "search=" + urllib.parse.quote("échec", safe=""))
    assert status == 200 and [item["id"] for item in json.loads(body)["items"]] == [R_ACCENT], (status, body)
    status, body = request(origin, "search=" + urllib.parse.quote("FUẞBALL", safe=""))
    assert status == 200 and [item["id"] for item in json.loads(body)["items"]] == [R_SHARP_S], (status, body)
    for partial_expansion in ("fuss", "s"):
        status, body = request(origin, "search=" + urllib.parse.quote(partial_expansion, safe=""))
        assert status == 200 and R_SHARP_S not in [
            item["id"] for item in json.loads(body)["items"]
        ], (status, body)
    print("Assigned-report search security checks passed")


if __name__ == "__main__":
    main()
