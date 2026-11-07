"""Regression check for checklist text that PostgreSQL cannot store faithfully."""

import json
import os
from http.client import HTTPConnection
from urllib.parse import urlsplit
from uuid import UUID

from checklist_save_api_test import snapshot, verify_fixture


def main():
    origin = urlsplit(os.environ["MP4_TEST_ORIGIN"])
    if origin.scheme != "http" or origin.hostname != "127.0.0.1" or not origin.port:
        raise SystemExit("Run this test against the local HTTP harness.")
    report_id = UUID(os.environ["MP4_TEST_REPORT_ID"])
    query = os.environ["MP4_TEST_SNAPSHOT_SQL"]
    headers = {
        **json.loads(os.environ["MP4_TEST_SESSION_HEADERS"]),
        "Content-Type": "application/json",
    }

    def save(step):
        conn = HTTPConnection(origin.hostname, origin.port, timeout=15)
        try:
            body = json.dumps({"steps": [step], "expectedDraftRevision": 2})
            conn.request("PUT", f"/api/reports/{report_id}/checklist/draft", body, headers)
            response = conn.getresponse()
            return response.status, json.load(response)
        finally:
            conn.close()

    before = snapshot(report_id, query)
    verify_fixture(before, os.environ["MP4_TEST_DEVELOPER_ID"])
    for invalid in ("\ud800", "before\x00after"):
        status, error = save(invalid)
        assert status == 400 and error["code"] == "INVALID_REQUEST", (status, error)
        assert snapshot(report_id, query) == before, "Rejected text changed stored state"

    valid = "Reproduce the export \U0001f680"
    assert save(valid) == (200, {"draftRevision": 3})
    assert snapshot(report_id, query) == {
        **before, "draftSteps": [valid], "draftRevision": 3,
    }, "Valid supplementary character did not round-trip"
    print("Text validation passed: malformed text rejected and supplementary character preserved.")


if __name__ == "__main__":
    main()
