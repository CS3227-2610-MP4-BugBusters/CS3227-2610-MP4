"""RPT-04 black-box tests for GET /api/reporter/reports (task 2.4). Rows are seeded directly
(older than 60 s) so the create rate limit is never involved. Each test uses fresh pool Reporters."""
import json
import unittest

from reporter_api_support import ApiCase, ISO, UUID_RE, fresh_reporter, request
from reporter_report_fixtures import PATH, seed, seed_at

MSG = "The request is invalid."


def listing(token, query=""):
    return request("GET", PATH + query, token=token)


def ok(test, res):
    st, h, payload = res
    test.assertEqual(st, 200, payload[:200])
    test.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
    test.assertEqual(h.get("cache-control"), "no-store")
    return json.loads(payload)


class ListOwnTest(ApiCase):
    def test_rpt04_empty_list_has_same_shape(self):
        token, _ = fresh_reporter()
        self.assertEqual(ok(self, listing(token)), {"items": [], "page": 1, "pageSize": 20, "totalItems": 0})

    def test_rpt04_two_reporters_are_isolated(self):
        t1, r1 = fresh_reporter()
        t2, r2 = fresh_reporter()
        a = seed(r1, "A", age_seconds=300)
        b = seed(r1, "B", age_seconds=200)
        c = seed(r2, "C", age_seconds=100)
        d1 = ok(self, listing(t1))
        self.assertEqual([i["id"] for i in d1["items"]], [b, a])
        self.assertEqual(d1["totalItems"], 2)
        d2 = ok(self, listing(t2))
        self.assertEqual([i["id"] for i in d2["items"]], [c])
        self.assertEqual(d2["totalItems"], 1)

    def test_rpt04_item_shape_only_five_fields(self):
        token, actor = fresh_reporter()
        rid = seed(actor, "Hello \U0001F600", "secret body")
        doc = ok(self, listing(token))
        item = doc["items"][0]
        self.assertEqual(sorted(item), ["createdAt", "id", "status", "title", "updatedAt"])
        self.assertEqual(item["id"], rid)
        self.assertRegex(item["id"], UUID_RE)
        self.assertEqual(item["title"], "Hello \U0001F600")
        self.assertEqual(item["status"], "SUBMITTED")
        self.assertRegex(item["createdAt"], ISO)
        self.assertRegex(item["updatedAt"], ISO)
        self.assertEqual(sorted(doc), ["items", "page", "pageSize", "totalItems"])
        self.assertIs(type(doc["page"]), int)
        self.assertIs(type(doc["totalItems"]), int)

    def test_rpt04_newest_first_ordering(self):
        token, actor = fresh_reporter()
        ids = [seed(actor, "r%d" % n, age_seconds=1000 - n * 100) for n in range(5)]  # last is newest
        doc = ok(self, listing(token))
        self.assertEqual([i["id"] for i in doc["items"]], list(reversed(ids)))

    def test_rpt04_equal_created_at_ordered_by_id_ascending(self):
        token, actor = fresh_reporter()
        ids = ["00000000-0000-4000-8000-00000000000%d" % n for n in (3, 1, 2)]
        for i in ids:
            seed_at(actor, i, "2026-01-01T00:00:00Z")
        newer = seed(actor, "newer")
        doc = ok(self, listing(token))
        self.assertEqual([i["id"] for i in doc["items"]], [newer] + sorted(ids))

    def test_rpt04_defaults_and_second_page(self):
        token, actor = fresh_reporter()
        ids = [seed(actor, "r%d" % n, age_seconds=5000 - n * 10) for n in range(25)]
        newest_first = list(reversed(ids))
        p1 = ok(self, listing(token))
        self.assertEqual((p1["page"], p1["pageSize"], p1["totalItems"]), (1, 20, 25))
        self.assertEqual([i["id"] for i in p1["items"]], newest_first[:20])
        p2 = ok(self, listing(token, "?page=2"))
        self.assertEqual((p2["page"], p2["pageSize"], p2["totalItems"]), (2, 20, 25))
        self.assertEqual([i["id"] for i in p2["items"]], newest_first[20:])

    def test_rpt04_explicit_page_size_and_pages_do_not_overlap(self):
        token, actor = fresh_reporter()
        ids = [seed(actor, "r%d" % n, age_seconds=5000 - n * 10) for n in range(5)]
        got = []
        for page in (1, 2, 3):
            doc = ok(self, listing(token, "?page=%d&pageSize=2" % page))
            self.assertEqual((doc["page"], doc["pageSize"], doc["totalItems"]), (page, 2, 5))
            got += [i["id"] for i in doc["items"]]
        self.assertEqual(got, list(reversed(ids)))

    def test_rpt04_page_past_last_is_empty_with_true_total(self):
        token, actor = fresh_reporter()
        for n in range(3):
            seed(actor, "r%d" % n)
        doc = ok(self, listing(token, "?page=9&pageSize=2"))
        self.assertEqual(doc, {"items": [], "page": 9, "pageSize": 2, "totalItems": 3})

    def test_rpt04_page_size_100_allowed(self):
        token, actor = fresh_reporter()
        seed(actor)
        doc = ok(self, listing(token, "?pageSize=100"))
        self.assertEqual(doc["pageSize"], 100)

    def test_rpt04_total_items_counts_only_own_reports(self):
        t1, r1 = fresh_reporter()
        _, r2 = fresh_reporter()
        seed(r1)
        for _ in range(4):
            seed(r2)
        self.assertEqual(ok(self, listing(t1))["totalItems"], 1)

    def test_rpt04_invalid_pagination_rejected_400(self):
        token, actor = fresh_reporter()
        seed(actor)
        bad = ["?page=0", "?page=-1", "?page=abc", "?page=1.5", "?page=", "?page=1e1", "?page=0x10",
               "?pageSize=0", "?pageSize=-5", "?pageSize=101", "?pageSize=abc", "?pageSize=", "?pageSize=2.0",
               "?page=1&page=2", "?pageSize=5&pageSize=5", "?page=1&page=1"]
        for q in bad:
            with self.subTest(query=q):
                self.assert_envelope(listing(token, q), 400, "VALIDATION_FAILED", MSG)

    def test_rpt04_unknown_parameters_rejected_400(self):
        token, actor = fresh_reporter()
        _, other = fresh_reporter()
        seed(other, "OtherTitle")
        for q in ("?reporterId=" + other, "?reporterId=" + actor, "?sort=title", "?status=SUBMITTED", "?q=x",
                  "?page=1&extra=1"):
            with self.subTest(query=q):
                self.assert_envelope(listing(token, q), 400, "VALIDATION_FAILED", MSG, forbidden=("OtherTitle",))


if __name__ == "__main__":
    unittest.main()
