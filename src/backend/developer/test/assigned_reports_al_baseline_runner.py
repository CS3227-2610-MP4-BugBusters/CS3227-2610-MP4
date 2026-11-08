"""Run AL-01..08 against the synthetic-session host with no list handler mounted."""

import json
import os
import subprocess
import sys
from http.client import HTTPConnection
from urllib.parse import urlsplit


ACTORS = {
    "d1-token": {"id": "00000000-0000-0000-0000-000000000401", "role": "DEVELOPER", "active": True},
    "d2-token": {"id": "00000000-0000-0000-0000-000000000402", "role": "DEVELOPER", "active": True},
    "reporter-token": {"id": "00000000-0000-0000-0000-000000000403", "role": "REPORTER", "active": True},
    "inactive-token": {"id": "00000000-0000-0000-0000-000000000404", "role": "DEVELOPER", "active": False},
    "empty-token": {"id": "00000000-0000-0000-0000-000000000405", "role": "DEVELOPER", "active": True},
    "maintainer-token": {"id": "00000000-0000-0000-0000-000000000406", "role": "MAINTAINER", "active": True},
}


def get(origin, token=None):
    address = urlsplit(origin)
    connection = HTTPConnection(address.hostname, address.port, timeout=5)
    headers = {"Authorization": "Bearer " + token} if token else {}
    try:
        connection.request("GET", "/__test/session", headers=headers)
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def main():
    if len(sys.argv) != 2:
        raise SystemExit("usage: assigned_reports_al_baseline_runner.py BUILD_DIR")
    server = subprocess.Popen(
        ["java", "--add-modules", "jdk.httpserver", "-cp", sys.argv[1],
         "mp4.developer.AssignedReportsBaselineHarness"],
        stdout=subprocess.PIPE, text=True,
    )
    try:
        origin = server.stdout.readline().strip()
        if not origin:
            raise SystemExit("Setup blocked: baseline harness did not start")
        os.environ["MP4_TEST_ORIGIN"] = origin
        status, body = get(origin)
        assert status == 401 and json.loads(body)["code"] == "UNAUTHENTICATED"
        for token, actor in ACTORS.items():
            status, body = get(origin, token)
            assert status == 200 and json.loads(body) == actor, token
        print("Synthetic session controls passed; route is intentionally unmounted.", flush=True)
        return subprocess.run([sys.executable, "-B", "src/backend/developer/test/assigned_reports_al_api_test.py"]).returncode
    finally:
        server.terminate()
        server.wait(timeout=5)


if __name__ == "__main__":
    raise SystemExit(main())
