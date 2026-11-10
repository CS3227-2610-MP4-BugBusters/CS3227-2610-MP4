"""RPT-09 scenario "Reporters cannot change status" (task 2.11): there is no Reporter close action.

Moved from the removed close_report_api_test.py: the Allow headers (now GET, PUT on a report) and the
"CLOSED set by SQL still locks edits and stays readable" checks."""
import json
import unittest
import uuid

from reporter_api_support import ApiCase, fresh_reporter, psql, request
from reporter_report_fixtures import PATH, edit, normalise_404, seed, snapshot

OWNER = OTHER = None


def setUpModule():
    global OWNER, OTHER
    OWNER = fresh_reporter()
    OTHER = fresh_reporter()


class StatusTest(ApiCase):
    def setUp(self):
        self.token, self.actor = OWNER
        self.rid = seed(self.actor, "Original title", "Original description")

    def not_found(self, res):
        self.assertEqual(res[0], 404, res[2][:200])
        self.assert_envelope(res, 404, "REPORT_NOT_FOUND", json.loads(res[2])["message"],
                             forbidden=("Original title", "OtherTitle"))

    def test_rpt09_no_close_action_subpaths_are_the_read_404_and_change_nothing(self):
        other_rid = seed(OTHER[1], "OtherTitle", "OtherBody")
        missing = str(uuid.uuid4())
        read_404 = normalise_404(request("GET", PATH + "/" + missing, token=self.token))
        before = {r: snapshot(r) for r in (self.rid, other_rid)}
        for label, rid in (("own", self.rid), ("other", other_rid), ("missing", missing)):
            for suffix in ("/close", "/close/", "/x"):
                for method in ("POST", "GET", "PUT"):
                    with self.subTest(report=label, suffix=suffix, method=method):
                        body = {"version": 0} if method != "GET" else None
                        res = request(method, PATH + "/" + rid + suffix, body=body, token=self.token)
                        self.not_found(res)
                        self.assertEqual(normalise_404(res), read_404)
        for r, snap in before.items():
            self.assertEqual(snapshot(r), snap)
        row = json.loads(snapshot(self.rid))
        self.assertEqual((row["status"], row["version"]), ("SUBMITTED", 0))

    def test_rpt09_status_key_in_edit_body_is_400_and_changes_nothing(self):
        before = snapshot(self.rid)
        for value in ("CLOSED", "SUBMITTED", "ASSIGNED", "", None, 1):
            with self.subTest(status=value):
                res = request("PUT", PATH + "/" + self.rid, token=self.token,
                              body={"title": "T", "description": "D", "version": 0, "status": value})
                self.assert_envelope(res, 400, "VALIDATION_FAILED", "The request is invalid.")
                self.assertEqual(snapshot(self.rid), before)
        res = request("PUT", PATH + "/" + self.rid, token=self.token, raw=b'{"title":"T","description":"D","version":0,"status":"CLOSED"}')
        self.assert_envelope(res, 400, "VALIDATION_FAILED", "The request is invalid.")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_allow_headers_report_is_get_put_collection_is_get_post(self):
        before = snapshot(self.rid)
        for path, methods, allow in ((PATH, ("PUT", "PATCH", "DELETE"), "GET, POST"),
                                     (PATH + "/" + self.rid, ("POST", "PATCH", "DELETE"), "GET, PUT")):
            for method in methods:
                with self.subTest(path=path, method=method):
                    res = request(method, path, body={"version": 0}, token=self.token)
                    self.assert_envelope(res, 405, "METHOD_NOT_ALLOWED", json.loads(res[2])["message"])
                    self.assertEqual(res[1].get("allow"), allow)
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_sql_closed_report_is_locked_for_edit_but_still_reads_and_lists_as_closed(self):
        token, actor = fresh_reporter()
        rid = seed(actor, "Keep me", "Body")
        psql("UPDATE reports SET status='CLOSED' WHERE id='%s'" % rid)
        before = snapshot(rid)
        res = edit(token, rid, "T", "D", 0)
        self.assert_envelope(res, 409, "REPORT_LOCKED", json.loads(res[2])["message"])
        self.assertEqual(snapshot(rid), before)
        st, _, payload = request("GET", PATH + "/" + rid, token=token)
        self.assertEqual((st, json.loads(payload)["status"]), (200, "CLOSED"))
        st, _, payload = request("GET", PATH, token=token)
        self.assertEqual([(i["id"], i["status"]) for i in json.loads(payload)["items"]], [(rid, "CLOSED")])


if __name__ == "__main__":
    unittest.main()
