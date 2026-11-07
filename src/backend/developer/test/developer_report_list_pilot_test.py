"""Session controls and route-presence probe for the proposed DR-01 pilot."""

import json
import os
import subprocess
import sys
from http.client import HTTPConnection
from urllib.parse import urlsplit


def request(origin, path, token=None):
    address = urlsplit(origin)
    connection = HTTPConnection(address.hostname, address.port, timeout=5)
    headers = {"Authorization": "Bearer " + token} if token else {}
    try:
        connection.request("GET", path, headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def start_server(classpath, mode=None):
    command = ["java", "--add-modules", "jdk.httpserver", "-cp", classpath,
               "mp4.developer.DeveloperReportListPilotHarness"]
    if mode:
        command.append(mode)
    server = subprocess.Popen(
        command,
        stdout=subprocess.PIPE, text=True,
    )
    origin = server.stdout.readline().strip()
    if not origin:
        server.wait(timeout=5)
        raise SystemExit("Setup blocked: pilot harness did not start")
    return server, origin


def stop_server(server):
    server.terminate()
    server.wait(timeout=5)


def main():
    classpath = sys.argv[1]
    server, origin = start_server(classpath)
    try:
        assert request(origin, "/__test/session")[0] == 401, "anonymous session should be denied"
        for token, expected in (("d1-token", "00000000-0000-0000-0000-000000000401"),
                                ("d2-token", "00000000-0000-0000-0000-000000000402")):
            status, body = request(origin, "/__test/session", token)
            assert status == 200
            assert json.loads(body)["id"] == expected
        for token in ("reporter-token", "inactive-token"):
            status, body = request(origin, "/__test/session", token)
            assert status == 200
            actor = json.loads(body)
            assert actor["role"] != "DEVELOPER" or not actor["active"]
        status, _ = request(origin, "/api/developer/reports", "d1-token")
        assert status == 404, f"expected absent pilot route, got HTTP {status}"
        print("Baseline controls passed; missing application route returned 404 (not behavioral Red)")
    finally:
        stop_server(server)

    if not os.environ.get("MP4_TEST_JDBC_URL"):
        print("Fixture-backed behavior: BLOCKED (set MP4_TEST_JDBC_URL and provide a disposable fixture database)")
        return

    server, origin = start_server(classpath, "fixture")
    try:
        status, body = request(origin, "/api/developer/reports")
        assert status == 401 and not body, "unauthenticated request must return no report data"
        for token in ("reporter-token", "inactive-token"):
            status, body = request(origin, "/api/developer/reports", token)
            assert status == 403 and not body, "wrong-role/inactive request must return no report data"

        status, body = request(origin, "/api/developer/reports", "d1-token")
        assert status == 200, f"D1 list failed: HTTP {status}"
        d1_before = json.loads(body)
        assert [item["id"] for item in d1_before["items"]] == [
            "00000000-0000-0000-0000-000000000301",
            "00000000-0000-0000-0000-000000000302",
        ]
        assert d1_before["items"][1]["status"] == "CLOSED"
        assert all(set(item) == {"id", "title", "status"} for item in d1_before["items"])
        assert all("description" not in item and "checklist" not in item for item in d1_before["items"])
        status, body = request(
            origin,
            "/api/developer/reports?developerId=00000000-0000-0000-0000-000000000402",
            "d1-token",
        )
        assert status == 200 and [item["id"] for item in json.loads(body)["items"]] == [
            "00000000-0000-0000-0000-000000000301",
            "00000000-0000-0000-0000-000000000302",
        ], "client-supplied Developer identity must be ignored"

        status, body = request(origin, "/api/developer/reports", "d2-token")
        assert status == 200
        d2_before = json.loads(body)
        assert [item["id"] for item in d2_before["items"]] == ["00000000-0000-0000-0000-000000000303"]

        database_uri = os.environ.get("MP4_TEST_DATABASE_URI")
        if not database_uri:
            raise SystemExit("Setup blocked: MP4_TEST_DATABASE_URI is required for the reassignment step")
        reassignment = subprocess.run(
            ["psql", database_uri, "-v", "ON_ERROR_STOP=1"],
            input=("UPDATE dr01_pilot_reports SET assignee_id = "
                   "'00000000-0000-0000-0000-000000000402' WHERE id = "
                   "'00000000-0000-0000-0000-000000000301';\n"),
            text=True, capture_output=True,
        )
        assert reassignment.returncode == 0, "fixture reassignment failed: " + reassignment.stderr

        d1_after = json.loads(request(origin, "/api/developer/reports", "d1-token")[1])
        d2_after = json.loads(request(origin, "/api/developer/reports", "d2-token")[1])
        assert "00000000-0000-0000-0000-000000000301" not in [item["id"] for item in d1_after["items"]]
        assert "00000000-0000-0000-0000-000000000301" in [item["id"] for item in d2_after["items"]]
        print("Fixture-backed DR-01 reference-adapter checks passed; this does not verify application behavior")
    finally:
        stop_server(server)


if __name__ == "__main__":
    main()
