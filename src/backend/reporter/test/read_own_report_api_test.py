"""RPT-05 black-box tests for GET /api/reporter/reports/{id} (task 2.4)."""
import json
import unittest
import uuid

from reporter_api_support import ApiCase, ISO, fresh_reporter, request
from reporter_report_fixtures import PATH, normalise_404, seed

TITLE = "Login é fails \U0001F600"
DESC = "  ## Steps\n1. Open /login\r\n\ttrailing  \n\n"


def read(token, rid):
    return request("GET", PATH + "/" + rid, token=token)


class ReadOwnTest(ApiCase):
    def test_rpt05_read_own_returns_stored_values_exactly(self):
        token, actor = fresh_reporter()
        rid = seed(actor, TITLE, DESC)
        st, h, payload = read(token, rid)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["createdAt", "description", "id", "status", "title", "updatedAt", "version"])
        self.assertEqual(doc["id"], rid)
        self.assertEqual(doc["title"], TITLE)
        self.assertEqual(doc["description"], DESC)
        self.assertEqual(doc["status"], "SUBMITTED")
        self.assertIs(type(doc["version"]), int)
        self.assertEqual(doc["version"], 0)
        self.assertRegex(doc["createdAt"], ISO)
        self.assertRegex(doc["updatedAt"], ISO)

    def test_rpt05_other_reporters_report_is_404_report_not_found(self):
        token, _ = fresh_reporter()
        _, other = fresh_reporter()
        rid = seed(other, "OtherTitle", "OtherBody")
        st, h, payload = read(token, rid)
        self.assertEqual(st, 404)
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["code", "message", "requestId"])
        self.assertEqual(doc["code"], "REPORT_NOT_FOUND")
        self.assertTrue(doc["message"])
        for leaked in ("OtherTitle", "OtherBody", rid):
            self.assertNotIn(leaked, payload.decode("utf-8"))
        self.assertEqual(h.get("cache-control"), "no-store")

    def test_rpt05_each_reporter_reads_only_their_own(self):
        t1, r1 = fresh_reporter()
        t2, r2 = fresh_reporter()
        a, b = seed(r1, "A"), seed(r2, "B")
        self.assertEqual(read(t1, a)[0], 200)
        self.assertEqual(read(t2, b)[0], 200)
        self.assertEqual(read(t1, b)[0], 404)
        self.assertEqual(read(t2, a)[0], 404)

    def test_rpt05_404_other_vs_nonexistent_vs_malformed_byte_identical_except_request_id(self):
        token, _ = fresh_reporter()
        _, other = fresh_reporter()
        cases = {"other": seed(other), "nonexistent": str(uuid.uuid4()), "malformed": "not-a-uuid"}
        results = {k: read(token, v) for k, v in cases.items()}
        for k, res in results.items():
            self.assertEqual(res[0], 404, k)
            self.assertEqual(json.loads(res[2])["code"], "REPORT_NOT_FOUND", k)
        base = normalise_404(results["other"])
        for k in ("nonexistent", "malformed"):
            with self.subTest(case=k):
                self.assertEqual(normalise_404(results[k]), base)
        self.assertNotEqual(json.loads(results["other"][2])["requestId"],
                            json.loads(results["nonexistent"][2])["requestId"])

    def test_rpt05_malformed_ids_are_404_identical(self):
        token, actor = fresh_reporter()
        rid = seed(actor)
        base = normalise_404(read(token, str(uuid.uuid4())))
        self.assertEqual(base[0], 404)
        bad = ["123", "not-a-uuid", rid.replace("-", ""), rid + "0", rid[:-1], "%20" + rid, rid + "%20", "..",
               "00000000-0000-0000-0000-00000000000g", "%7B" + rid + "%7D", "%00", "-1", "null"]
        for b in bad:
            with self.subTest(id=b):
                self.assertEqual(normalise_404(read(token, b)), base)


if __name__ == "__main__":
    unittest.main()
