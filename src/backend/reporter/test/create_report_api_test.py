"""RPT-01 and RPT-03 black-box tests for POST /api/reporter/reports (task 2.3)."""
import json
import os
import unittest
from datetime import datetime, timezone

from reporter_api_support import ApiCase, ISO, UUID_RE, count, fresh_reporter, psql, request

PATH = "/api/reporter/reports"
FORBIDDEN_MSG = "The request is invalid."


def stored(report_id):
    """Row snapshot via psql; text columns come back as hex so bytes are exact."""
    row = psql("SELECT reporter_id, coalesce(assignee_id::text,'<null>'), status, version, "
               "encode(convert_to(title,'UTF8'),'hex'), encode(convert_to(description,'UTF8'),'hex'), "
               "created_at IS NOT NULL, updated_at IS NOT NULL, created_at = updated_at, "
               "to_char(created_at AT TIME ZONE 'UTC','YYYY-MM-DD\"T\"HH24:MI:SS.MS\"Z\"') "
               "FROM reports WHERE id='%s'" % report_id)
    if not row:
        return None
    f = row.split("|")
    return {"reporter_id": f[0], "assignee": f[1], "status": f[2], "version": int(f[3]),
            "title": bytes.fromhex(f[4]).decode("utf-8"), "description": bytes.fromhex(f[5]).decode("utf-8"),
            "ts_set": f[6] == "t" and f[7] == "t", "ts_equal": f[8] == "t", "created_iso": f[9]}


def create(token, title="Login fails", description="## Steps\n1. Open /login", ascii_json=False):
    raw = json.dumps({"title": title, "description": description}, ensure_ascii=ascii_json).encode("utf-8")
    return request("POST", PATH, raw=raw, token=token)


class CreateReportTest(ApiCase):
    def test_rpt01_valid_submission_201_shape_and_headers(self):
        token, actor = fresh_reporter()
        st, h, payload = create(token)
        self.assertEqual(st, 201, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["createdAt", "description", "id", "status", "title", "updatedAt", "version"])
        self.assertRegex(doc["id"], UUID_RE)
        self.assertEqual(h.get("location"), "/api/reporter/reports/" + doc["id"])
        self.assertEqual(doc["title"], "Login fails")
        self.assertEqual(doc["description"], "## Steps\n1. Open /login")
        self.assertEqual(doc["status"], "SUBMITTED")
        self.assertIs(type(doc["version"]), int)
        self.assertEqual(doc["version"], 0)
        self.assertRegex(doc["createdAt"], ISO)
        self.assertEqual(doc["createdAt"], doc["updatedAt"])
        created = datetime.strptime(doc["createdAt"], "%Y-%m-%dT%H:%M:%S.%fZ").replace(tzinfo=timezone.utc)
        self.assertLess(abs((datetime.now(timezone.utc) - created).total_seconds()), 120)
        for key in ("reporterId", "assigneeId"):
            self.assertNotIn(key, doc)

    def test_rpt01_valid_submission_stored_row_server_owned_values(self):
        token, actor = fresh_reporter()
        st, _, payload = create(token)
        self.assertEqual(st, 201, payload[:200])
        doc = json.loads(payload)
        row = stored(doc["id"])
        self.assertIsNotNone(row)
        self.assertEqual(row["reporter_id"], actor)
        self.assertEqual(row["assignee"], "<null>")
        self.assertEqual(row["status"], "SUBMITTED")
        self.assertEqual(row["version"], 0)
        self.assertTrue(row["ts_set"])
        self.assertTrue(row["ts_equal"])
        self.assertEqual(row["created_iso"], doc["createdAt"])
        self.assertEqual(count(actor), 1)

    def test_rpt01_valid_submission_appears_in_own_list(self):
        token, _ = fresh_reporter()
        st, _, payload = create(token)
        self.assertEqual(st, 201, payload[:200])
        rid = json.loads(payload)["id"]
        st, _, listing = request("GET", PATH, token=token)
        self.assertEqual(st, 200)
        self.assertEqual([i["id"] for i in json.loads(listing)["items"]], [rid])

    def test_rpt01_ids_are_server_generated_and_distinct(self):
        a, _ = fresh_reporter()
        b, _ = fresh_reporter()
        ra, rb = create(a), create(b)
        self.assertEqual((ra[0], rb[0]), (201, 201))
        self.assertNotEqual(json.loads(ra[2])["id"], json.loads(rb[2])["id"])

    def test_rpt01_server_owned_and_unsupported_keys_rejected_nothing_stored(self):
        token, actor = fresh_reporter()
        extras = {"id": "7d3c0b1e-0000-4000-8000-000000000001", "reporterId": actor, "status": "CLOSED",
                  "version": 5, "assigneeId": actor, "reproductionSteps": "x", "environment": "y",
                  "createdAt": "2026-01-01T00:00:00.000Z", "updatedAt": "2026-01-01T00:00:00.000Z",
                  "unknownKey": 1}
        before = count()
        for key, value in extras.items():
            with self.subTest(key=key):
                body = {"title": "T", "description": "D", key: value}
                res = request("POST", PATH, body=body, token=token)
                self.assert_envelope(res, 400, "VALIDATION_FAILED", FORBIDDEN_MSG)
        self.assertEqual(count(), before)
        self.assertEqual(count(actor), 0)

    def test_rpt01_identity_in_headers_or_query_does_not_select_actor(self):
        token, actor = fresh_reporter()
        other = "00000000-0000-4000-8000-0000000000aa"
        st, h, payload = request("POST", PATH + "?reporterId=" + other,
                                 body={"title": "T", "description": "D"}, token=token,
                                 headers={"X-Reporter-Id": other, "X-Role": "MAINTAINER"})
        self.assertEqual(st, 201, payload[:200])
        self.assertEqual(stored(json.loads(payload)["id"])["reporter_id"], actor)

    def test_rpt01_no_session_is_401(self):
        before = count()
        for token in (None, "not-a-real-token"):
            with self.subTest(token=token):
                res = request("POST", PATH, body={"title": "T", "description": "D"}, token=token)
                self.assert_envelope(res, 401, "UNAUTHENTICATED", self._msg(res))
        self.assertEqual(count(), before)

    def _msg(self, res):
        return json.loads(res[2]).get("message", "<missing>")

    def test_rpt01_inactive_or_non_reporter_is_403_nothing_stored(self):
        before = count()
        for name in ("R3", "D1", "M1"):
            with self.subTest(actor=name):
                res = request("POST", PATH, body={"title": "T", "description": "D"},
                              token=os.environ["MP4_TEST_TOKEN_" + name])
                self.assert_envelope(res, 403, "FORBIDDEN", self._msg(res))
        self.assertEqual(count(), before)

    def test_rpt01_session_checked_before_validation(self):
        res = request("POST", PATH, raw=b"{not json", token=None)
        self.assert_envelope(res, 401, "UNAUTHENTICATED", self._msg(res))
        res = request("POST", PATH, body={"title": "", "description": ""}, token="bogus")
        self.assert_envelope(res, 401, "UNAUTHENTICATED", self._msg(res))

    SAMPLES = {
        "spaces": ("  padded title  ", "  leading and trailing  \n\n   "),
        "crlf_tabs": ("tab\there", "line1\r\nline2\r\n\ttabbed\r\n"),
        "emoji": ("\U0001F600 smile \U0001F680", "rocket \U0001F680 and \U0001F468‍\U0001F469‍\U0001F467"),
        "combining": ("é vs é", "ä and ä and Å vs Å"),
        "rtl": ("שלום مرحبا", "‮evil‬ שלום مرحبا"),
        "script": ("<b>bold</b> & \"q\"", "<script>alert(1)</script> &amp; <img src=x onerror=alert(1)>"),
        "markdown": ("# not a heading", "```python\nprint('x')  \n```\n\n| a | b |\n|---|---|\n| 1 | 2 |  \n\ntrailing  "),
        "nbsp_ideographic": (" x　", "　lead  and  "),
    }

    def test_rpt03_round_trip_byte_identical_response_column_and_read(self):
        for name, (title, description) in self.SAMPLES.items():
            for ascii_json in (False, True):
                with self.subTest(sample=name, ascii_json=ascii_json):
                    token, _ = fresh_reporter()
                    st, h, payload = create(token, title, description, ascii_json)
                    self.assertEqual(st, 201, payload[:200])
                    doc = json.loads(payload)
                    self.assertEqual(doc["title"].encode("utf-8"), title.encode("utf-8"))
                    self.assertEqual(doc["description"].encode("utf-8"), description.encode("utf-8"))
                    row = stored(doc["id"])
                    self.assertEqual(row["title"].encode("utf-8"), title.encode("utf-8"))
                    self.assertEqual(row["description"].encode("utf-8"), description.encode("utf-8"))
                    st, h, got = request("GET", PATH + "/" + doc["id"], token=token)
                    self.assertEqual(st, 200, got[:200])
                    self.assertEqual(h.get("cache-control"), "no-store")
                    read = json.loads(got)
                    self.assertEqual(read["title"].encode("utf-8"), title.encode("utf-8"))
                    self.assertEqual(read["description"].encode("utf-8"), description.encode("utf-8"))
                    self.assertEqual(read, doc)

    def test_rpt03_responses_contain_no_server_generated_html(self):
        token, _ = fresh_reporter()
        st, h, payload = create(token, "plain", "# Heading\n**bold** <em>x</em>")
        self.assertEqual(st, 201, payload[:200])
        self.assertTrue(h["content-type"].startswith("application/json"))
        self.assertEqual(json.loads(payload)["description"], "# Heading\n**bold** <em>x</em>")
        self.assertNotIn(b"<h1>", payload)
        self.assertNotIn(b"<strong>", payload)

    def test_rpt03_both_fields_kept_verbatim_even_when_only_whitespace_inside(self):
        token, _ = fresh_reporter()
        st, _, payload = create(token, " a ", " b ")
        self.assertEqual(st, 201, payload[:200])
        doc = json.loads(payload)
        self.assertEqual((doc["title"], doc["description"]), (" a ", " b "))


if __name__ == "__main__":
    unittest.main(verbosity=2)
