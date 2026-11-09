"""HTTP regression for safe report-version serialization after Share."""

import json
import os
import subprocess
from http.client import HTTPConnection
from urllib.parse import urlsplit
from uuid import UUID

MAX_SAFE_INTEGER = 9_007_199_254_740_991


def snapshot(report_id):
    result = subprocess.run(
        ["psql", "-XqAt", "-v", "ON_ERROR_STOP=1", "-v", f"report_id={report_id}",
         "-f", os.environ["MP4_TEST_SNAPSHOT_SQL"]],
        check=True, capture_output=True, text=True, timeout=15,
    )
    return json.loads(result.stdout)


def post(origin, headers, report_id, revision):
    connection = HTTPConnection(origin.hostname, origin.port, timeout=15)
    try:
        connection.request(
            "POST", f"/api/reports/{report_id}/checklist/share",
            body=json.dumps({"expectedDraftRevision": revision,
                             "expectedReportVersion": MAX_SAFE_INTEGER}),
            headers={**headers, "Content-Type": "application/json"},
        )
        response = connection.getresponse()
        return response.status, response.read()
    finally:
        connection.close()


def main():
    report_id = UUID(os.environ["MP4_TEST_REPORT_ID"])
    headers = json.loads(os.environ["MP4_TEST_SESSION_HEADERS"])
    origin = urlsplit(os.environ["MP4_TEST_ORIGIN"])

    # An unchanged Share at the maximum accepted input version is representable and remains a no-op.
    subprocess.run(
        ["psql", "-Xq", "-v", "ON_ERROR_STOP=1", "-c",
         "UPDATE reports SET version = 9007199254740991 "
         f"WHERE id = '{report_id}'::uuid; "
         "UPDATE investigation_checklists SET shared_steps = draft_steps "
         f"WHERE report_id = '{report_id}'::uuid"],
        check=True, capture_output=True, text=True, timeout=15,
    )
    unchanged_before = snapshot(report_id)
    status, raw = post(origin, headers, report_id, unchanged_before["draftRevision"])
    assert status == 200, f"Expected unchanged Share HTTP 200, got {status}: {raw!r}"
    assert json.loads(raw) == {"reportVersion": MAX_SAFE_INTEGER, "changed": False}
    assert snapshot(report_id) == unchanged_before, "Unchanged Share mutated max-version report"

    # Restore a changed publication while keeping the valid maximum input version.
    subprocess.run(
        ["psql", "-Xq", "-v", "ON_ERROR_STOP=1", "-c",
         "UPDATE investigation_checklists SET shared_steps = ARRAY['previously shared'] "
         f"WHERE report_id = '{report_id}'::uuid"],
        check=True, capture_output=True, text=True, timeout=15,
    )
    changed_before = snapshot(report_id)
    status, raw = post(origin, headers, report_id, changed_before["draftRevision"])
    assert status == 409, f"Expected HTTP 409 VERSION_LIMIT, got {status}: {raw!r}"
    error = json.loads(raw)
    assert error.get("code") == "VERSION_LIMIT", f"Expected VERSION_LIMIT, got {error!r}"
    assert snapshot(report_id) == changed_before, (
        f"Changed Share returned HTTP {status} ({raw!r}) after mutating checklist/report/proposal "
        "at the JS safe-integer limit"
    )
    print("Safe-version HTTP checks passed: unchanged max-version Share succeeded; changed max-version Share failed without mutation")


if __name__ == "__main__":
    main()
