"""DR-01 AL-01..08 black-box checks for the assigned-reports application route."""

import json
import os
import subprocess
import unittest
import urllib.error
import urllib.parse
import urllib.request


D1 = "00000000-0000-0000-0000-000000000401"
D2 = "00000000-0000-0000-0000-000000000402"
EMPTY = "00000000-0000-0000-0000-000000000405"
R301 = "00000000-0000-0000-0000-000000000301"
R302 = "00000000-0000-0000-0000-000000000302"
R322 = "00000000-0000-0000-0000-000000000322"
ROUTE = "/api/developer/reports"


def request(origin, path, token=None):
    headers = {"Accept": "application/json"}
    if token:
        headers["Authorization"] = "Bearer " + token
    req = urllib.request.Request(origin.rstrip("/") + path, headers=headers)
    try:
        with urllib.request.urlopen(req, timeout=5) as response:
            return response.status, response.read()
    except urllib.error.HTTPError as error:
        return error.code, error.read()


def error_payload(status, body, expected, code):
    assert status == expected, f"expected HTTP {expected}, got {status}: {body!r}"
    result = json.loads(body)
    assert result["code"] == code and result["message"] and result["requestId"]
    assert "items" not in result
    return result


class AssignedReportsAcceptance(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.origin = os.environ["MP4_TEST_ORIGIN"]
        cls.tokens = {
            "d1": os.environ.get("MP4_TEST_D1_TOKEN", "d1-token"),
            "d2": os.environ.get("MP4_TEST_D2_TOKEN", "d2-token"),
            "empty": os.environ.get("MP4_TEST_EMPTY_TOKEN", "empty-token"),
            "reporter": os.environ.get("MP4_TEST_REPORTER_TOKEN", "reporter-token"),
            "maintainer": os.environ.get("MP4_TEST_MAINTAINER_TOKEN", "maintainer-token"),
            "inactive": os.environ.get("MP4_TEST_INACTIVE_TOKEN", "inactive-token"),
        }
        uri = os.environ["MP4_TEST_DATABASE_URI"]
        name = os.environ.get("MP4_TEST_DATABASE_NAME")
        parsed = urllib.parse.urlsplit(uri)
        if name != "mp4_assigned_reports_test" or parsed.path.lstrip("/") != name:
            raise RuntimeError("Use the disposable database named mp4_assigned_reports_test")
        cls.database_uri = uri
        cls.fixture = os.path.join(os.path.dirname(__file__), "assigned-reports-al-fixture.sql")

    def setUp(self):
        reset = subprocess.run(["psql", self.database_uri, "-X", "-v", "ON_ERROR_STOP=1", "-f", self.fixture],
                               text=True, capture_output=True)
        self.assertEqual(reset.returncode, 0, "disposable fixture setup failed: " + reset.stderr)
        # Confirm the committed fixture before using the HTTP target.
        check = subprocess.run(["psql", self.database_uri, "-XqAt", "-v", "ON_ERROR_STOP=1", "-c",
                                "SELECT count(*) FROM reports WHERE assignee_id = '" + D1 + "'"],
                               text=True, capture_output=True)
        self.assertEqual(check.returncode, 0, "fixture verification failed: " + check.stderr)
        self.assertEqual(check.stdout.strip(), "22")

    def test_al_01_only_current_developer_assignments_and_minimal_projection(self):
        status, body = request(self.origin, ROUTE, self.tokens["d1"])
        self.assertEqual(status, 200, f"expected HTTP 200, got {status}: {body!r}")
        result = json.loads(body)
        self.assertEqual(result["totalItems"], 22)
        self.assertEqual(len(result["items"]), 20)
        self.assertTrue(all(301 <= int(item["id"][-3:]) <= 320 for item in result["items"]))
        self.assertTrue(all(set(item) == {"id", "title", "status"} for item in result["items"]))

    def test_al_02_active_developer_with_no_assignments_gets_empty_envelope(self):
        status, body = request(self.origin, ROUTE, self.tokens["empty"])
        self.assertEqual(status, 200, f"expected HTTP 200, got {status}: {body!r}")
        self.assertEqual(json.loads(body), {"items": [], "page": 1, "pageSize": 20, "totalItems": 0})

    def test_al_03_closed_assigned_report_is_included(self):
        status, body = request(self.origin, ROUTE, self.tokens["d1"])
        self.assertEqual(status, 200, f"expected HTTP 200, got {status}: {body!r}")
        result = json.loads(body)
        self.assertIn({"id": R302, "title": "Closed report", "status": "CLOSED"}, result["items"])

    def test_al_04_committed_reassignment_is_seen_by_both_developers(self):
        move = subprocess.run(["psql", self.database_uri, "-X", "-v", "ON_ERROR_STOP=1", "-c",
                               "UPDATE reports SET assignee_id = '" + D2 + "' WHERE id = '" + R301 + "'"],
                              text=True, capture_output=True)
        self.assertEqual(move.returncode, 0, "reassignment did not commit: " + move.stderr)
        d1_status, d1_body = request(self.origin, ROUTE, self.tokens["d1"])
        self.assertEqual(d1_status, 200, f"expected HTTP 200, got {d1_status}: {d1_body!r}")
        d2_status, d2_body = request(self.origin, ROUTE, self.tokens["d2"])
        self.assertEqual(d2_status, 200, f"expected HTTP 200, got {d2_status}: {d2_body!r}")
        self.assertNotIn(R301, [item["id"] for item in json.loads(d1_body)["items"]])
        self.assertIn(R301, [item["id"] for item in json.loads(d2_body)["items"]])
        self.assertEqual(json.loads(d1_body)["totalItems"], 21)
        self.assertEqual(json.loads(d2_body)["totalItems"], 2)

    def test_al_05_missing_session_is_401_without_report_data(self):
        error_payload(*request(self.origin, ROUTE), 401, "UNAUTHENTICATED")

    def test_al_06_reporter_maintainer_and_inactive_developer_are_denied(self):
        for role in ("reporter", "maintainer", "inactive"):
            with self.subTest(role=role):
                error_payload(*request(self.origin, ROUTE, self.tokens[role]), 403, "FORBIDDEN")

    def test_al_07_client_supplied_developer_identity_is_rejected(self):
        status, body = request(self.origin, ROUTE + "?developerId=" + D2, self.tokens["d1"])
        error_payload(status, body, 400, "VALIDATION_FAILED")

    def test_al_08_default_page_is_bounded_ordered_and_counts_only_own_assignments(self):
        status, body = request(self.origin, ROUTE, self.tokens["d1"])
        self.assertEqual(status, 200, f"expected HTTP 200, got {status}: {body!r}")
        result = json.loads(body)
        ids = [item["id"] for item in result["items"]]
        self.assertEqual((result["page"], result["pageSize"], result["totalItems"]), (1, 20, 22))
        self.assertEqual(ids, sorted(ids))
        self.assertEqual(len(ids), 20)
        self.assertNotIn("00000000-0000-0000-0000-000000000330", ids)


if __name__ == "__main__":
    unittest.main(verbosity=2)
