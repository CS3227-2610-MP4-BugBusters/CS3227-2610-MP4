"""Reject request bodies that are not valid UTF-8 without changing stored notes."""

import json
import os
import subprocess
from http.client import HTTPConnection, HTTPSConnection
from urllib.parse import urlsplit


def snapshot(report_id, snapshot_sql):
    result = subprocess.run(
        ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-v", f"report_id={report_id}",
         "-f", snapshot_sql],
        check=True, capture_output=True, text=True, timeout=15,
    )
    return json.loads(result.stdout)


def main():
    origin = urlsplit(os.environ["MP4_TEST_ORIGIN"])
    headers = json.loads(os.environ["MP4_TEST_SESSION_HEADERS"])
    report_id = os.environ["MP4_TEST_REPORT_ID"]
    snapshot_sql = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    path = f"/api/reports/{report_id}/private-notes"
    before = snapshot(report_id, snapshot_sql)
    prefix = b'{"text":"'
    suffix = b'","expectedNotesRevision":2}'
    malformed = [
        prefix + b"\xc1\x81" + suffix,
        prefix + b"\xc0\xaf" + suffix,
        '{"text":"x","expectedNotesRevision":2}'.encode("utf-16"),
        '{"text":"x","expectedNotesRevision":2}'.encode("utf-16-le"),
        '{"text":"x","expectedNotesRevision":2}'.encode("utf-16-be"),
        '{"text":"x","expectedNotesRevision":2}'.encode("utf-32"),
    ]
    connection_type = HTTPSConnection if origin.scheme == "https" else HTTPConnection
    for raw_body in malformed:
        connection = connection_type(origin.hostname, origin.port, timeout=15)
        try:
            request_headers = dict(headers)
            request_headers["Content-Type"] = "application/json"
            connection.request("PUT", path, body=raw_body, headers=request_headers)
            response = connection.getresponse()
            body = json.loads(response.read())
            assert response.status == 400, f"Expected 400, got {response.status}: {body}"
            assert body.get("code") == "INVALID_REQUEST", f"Unexpected error: {body}"
        finally:
            connection.close()
    assert snapshot(report_id, snapshot_sql) == before, "Invalid UTF-8 changed stored state"
    print("Private-notes malformed UTF-8 and UTF-16/32 bodies rejected without mutation")


if __name__ == "__main__":
    main()
