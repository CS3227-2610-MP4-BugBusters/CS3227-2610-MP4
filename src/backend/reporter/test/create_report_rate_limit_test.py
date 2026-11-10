"""RPT-08 black-box tests for the create rate limit (task 2.6): 3 creates per rolling 60 s per Reporter.

Rollover uses backdated fixture rows (design D10); concurrency uses real parallel requests (the harness
serves with a multi-thread executor). Every test takes its own fresh pool Reporter."""
import json
import threading
import unittest

from reporter_api_support import ApiCase, count, fresh_reporter, request
from reporter_report_fixtures import PATH, backdate, backdate_all, create, seed

import os


def made(test, token):
    st, _, payload = create(token)
    test.assertEqual(st, 201, payload[:200])
    return json.loads(payload)["id"]


class RateLimitTest(ApiCase):
    def limited(self, res):
        st, h, payload = res
        self.assertEqual(st, 429, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["code", "message", "requestId"])
        self.assertEqual(doc["code"], "RATE_LIMITED")
        self.assertTrue(isinstance(doc["message"], str) and doc["message"].strip())
        self.assertTrue(isinstance(doc["requestId"], str) and doc["requestId"])
        return h

    def test_rpt08_fourth_create_within_a_minute_is_429_and_stores_nothing(self):
        token, actor = fresh_reporter()
        for _ in range(3):
            made(self, token)
        h = self.limited(create(token, "FourthTitle", "FourthBody"))
        retry = h.get("retry-after")
        self.assertRegex(retry or "", r"^[0-9]+$")
        self.assertGreaterEqual(int(retry), 1)
        self.assertLessEqual(int(retry), 60)
        self.assertEqual(count(actor), 3)

    def test_rpt08_429_body_does_not_echo_submitted_text(self):
        token, _ = fresh_reporter()
        for _ in range(3):
            made(self, token)
        res = create(token, "FourthTitle", "FourthBody")
        self.limited(res)
        self.assertNotIn(b"FourthTitle", res[2])
        self.assertNotIn(b"FourthBody", res[2])

    def test_rpt08_retry_after_is_whole_seconds_until_oldest_create_is_60s_old(self):
        token, actor = fresh_reporter()
        ids = [made(self, token) for _ in range(3)]
        backdate(ids[0], 30)  # oldest is now 30 s old -> about 30 s remain
        h = self.limited(create(token))
        self.assertRegex(h.get("retry-after") or "", r"^[0-9]+$")
        self.assertIn(int(h["retry-after"]), (29, 30))
        self.assertEqual(count(actor), 3)

    def test_rpt08_window_rolls_over_when_creates_are_older_than_60s(self):
        token, actor = fresh_reporter()
        for _ in range(3):
            made(self, token)
        self.limited(create(token))
        backdate_all(actor, 61)
        made(self, token)
        self.assertEqual(count(actor), 4)

    def test_rpt08_rolling_window_frees_one_slot_when_only_oldest_expires(self):
        token, actor = fresh_reporter()
        ids = [made(self, token) for _ in range(3)]
        backdate(ids[0], 61)
        made(self, token)  # window now holds 2 old-enough-expired -> 2 remaining + new = 3
        self.limited(create(token))
        self.assertEqual(count(actor), 4)

    def test_rpt08_seeded_recent_rows_count_toward_window(self):
        token, actor = fresh_reporter()
        for _ in range(3):
            seed(actor, age_seconds=5)
        self.limited(create(token))
        self.assertEqual(count(actor), 3)

    def test_rpt08_seeded_old_rows_do_not_count(self):
        token, actor = fresh_reporter()
        for _ in range(5):
            seed(actor, age_seconds=120)
        for _ in range(3):
            made(self, token)
        self.limited(create(token))
        self.assertEqual(count(actor), 8)

    def test_rpt08_five_parallel_creates_leave_exactly_three_rows(self):
        for attempt in range(3):
            with self.subTest(attempt=attempt):
                token, actor = fresh_reporter()
                barrier = threading.Barrier(5)
                results = [None] * 5

                def worker(i):
                    barrier.wait(timeout=10)
                    results[i] = create(token, "P%d" % i, "D")

                threads = [threading.Thread(target=worker, args=(i,)) for i in range(5)]
                for t in threads:
                    t.start()
                for t in threads:
                    t.join(timeout=30)
                statuses = sorted(r[0] for r in results)
                self.assertEqual(statuses, [201, 201, 201, 429, 429], statuses)
                for r in results:
                    if r[0] == 429:
                        self.limited(r)
                self.assertEqual(count(actor), 3)

    def test_rpt08_limit_is_per_reporter(self):
        t1, r1 = fresh_reporter()
        t2, r2 = fresh_reporter()
        for _ in range(3):
            made(self, t1)
        self.limited(create(t1))
        for _ in range(3):
            made(self, t2)
        self.assertEqual((count(r1), count(r2)), (3, 3))
        self.limited(create(t2))

    def test_rpt08_invalid_requests_are_not_counted(self):
        token, actor = fresh_reporter()
        for _ in range(5):
            st, _, payload = request("POST", PATH, body={"title": "", "description": "D"}, token=token)
            self.assertEqual(st, 400, payload[:200])
        self.assertEqual(request("POST", PATH, raw=b"{bad", token=token)[0], 400)
        for _ in range(3):
            made(self, token)
        self.limited(create(token))
        self.assertEqual(count(actor), 3)

    def test_rpt08_429_responses_are_not_counted(self):
        token, actor = fresh_reporter()
        for _ in range(3):
            made(self, token)
        for _ in range(4):
            self.limited(create(token))
        backdate_all(actor, 61)
        for _ in range(3):
            made(self, token)
        self.limited(create(token))
        self.assertEqual(count(actor), 6)

    def test_rpt08_session_failures_are_not_counted_for_reporter(self):
        token, actor = fresh_reporter()
        for tok in (None, "bogus", os.environ["MP4_TEST_TOKEN_D1"], os.environ["MP4_TEST_TOKEN_R3"]):
            self.assertIn(create(tok)[0], (401, 403))
        for _ in range(3):
            made(self, token)
        self.limited(create(token))

    def test_rpt08_check_order_session_then_validation_then_limit(self):
        token, actor = fresh_reporter()
        for _ in range(3):
            made(self, token)
        # over the limit: invalid -> 400 (validation before limit), valid -> 429
        res = request("POST", PATH, body={"title": "", "description": "D"}, token=token)
        self.assert_envelope(res, 400, "VALIDATION_FAILED", "The request is invalid.")
        res = request("POST", PATH, body={"title": "T", "description": "D", "id": "x"}, token=token)
        self.assert_envelope(res, 400, "VALIDATION_FAILED", "The request is invalid.")
        self.limited(create(token))
        # session failures never reach the limit
        self.assertEqual(create(None)[0], 401)
        self.assertEqual(create(os.environ["MP4_TEST_TOKEN_D1"])[0], 403)
        self.assertEqual(count(actor), 3)

    def test_rpt08_list_and_read_are_not_rate_limited(self):
        token, actor = fresh_reporter()
        ids = [made(self, token) for _ in range(3)]
        self.limited(create(token))
        for _ in range(5):
            self.assertEqual(request("GET", PATH, token=token)[0], 200)
            self.assertEqual(request("GET", PATH + "/" + ids[0], token=token)[0], 200)


if __name__ == "__main__":
    unittest.main()
