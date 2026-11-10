"""RPT-10 black-box tests for POST /api/reporter/reports/{id}/close (task 2.11).

Reports are seeded straight into the database (age 3600 s, version 0) so one pool Reporter can hold many
without touching the create limit. Locks are set with SQL. Races use real parallel requests plus a held
database lock for the deterministic ordering."""
import json
import os
import threading
import time
import unittest
import uuid

from reporter_api_support import ApiCase, count, fresh_reporter, psql, request
from reporter_report_fixtures import (PATH, assign_holding_lock, assign_sql, close, create, edit, normalise_404,
                                      seed, snapshot)

ENV = os.environ
INVALID = ("VALIDATION_FAILED", "The request is invalid.")
M1 = ENV["MP4_TEST_ID_M1"]
OWNER = OTHER = None


def setUpModule():
    global OWNER, OTHER
    OWNER = fresh_reporter()
    OTHER = fresh_reporter()


def code_of(res):
    return json.loads(res[2])["code"]


class CloseTest(ApiCase):
    def setUp(self):
        self.token, self.actor = OWNER
        self.rid = seed(self.actor, "Original title", "Original **description**")

    def error(self, res, status, code, forbidden=()):
        self.assert_envelope(res, status, code, json.loads(res[2])["message"], forbidden=forbidden)

    def invalid(self, res):
        self.assert_envelope(res, 400, *INVALID)

    def post_raw(self, raw, rid=None, token=None):
        return request("POST", PATH + "/" + (rid or self.rid) + "/close", raw=raw, token=token or self.token)

    def post_body(self, body, rid=None):
        return request("POST", PATH + "/" + (rid or self.rid) + "/close", body=body, token=self.token)

    # ---- success
    def test_rpt10_close_unassigned_returns_200_closed_version_plus_one_text_unchanged(self):
        created = json.loads(request("GET", PATH + "/" + self.rid, token=self.token)[2])
        st, h, payload = close(self.token, self.rid, 0)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["createdAt", "description", "id", "status", "title", "updatedAt", "version"])
        self.assertEqual((doc["id"], doc["title"], doc["description"], doc["status"], doc["version"]),
                         (self.rid, "Original title", "Original **description**", "CLOSED", 1))
        self.assertEqual(doc["createdAt"], created["createdAt"])
        self.assertGreater(doc["updatedAt"], created["updatedAt"])
        row = json.loads(snapshot(self.rid))
        self.assertEqual((row["status"], row["version"], row["assignee_id"]), ("CLOSED", 1, None))

    def test_rpt10_closed_report_still_reads_and_is_listed_as_closed(self):
        token, actor = fresh_reporter()
        rid = seed(actor, "Keep me", "Body")
        st, _, payload = close(token, rid, 0)
        self.assertEqual(st, 200, payload[:200])
        doc = json.loads(payload)
        st, _, payload = request("GET", PATH + "/" + rid, token=token)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(json.loads(payload), doc)
        st, _, payload = request("GET", PATH, token=token)
        self.assertEqual(st, 200, payload[:200])
        listed = json.loads(payload)
        self.assertEqual(listed["totalItems"], 1)
        self.assertEqual([(i["id"], i["status"], i["title"]) for i in listed["items"]],
                         [(rid, "CLOSED", "Keep me")])

    # ---- locked
    def test_rpt10_closing_again_is_locked_and_unchanged(self):
        self.assertEqual(close(self.token, self.rid, 0)[0], 200)
        before = snapshot(self.rid)
        for version in (1, 0, 99):
            with self.subTest(version=version):
                self.error(close(self.token, self.rid, version), 409, "REPORT_LOCKED")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_editing_a_closed_report_is_locked_and_unchanged(self):
        self.assertEqual(close(self.token, self.rid, 0)[0], 200)
        before = snapshot(self.rid)
        self.error(edit(self.token, self.rid, "T", "D", 1), 409, "REPORT_LOCKED")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_assigned_report_is_locked_and_unchanged(self):
        assign_sql(self.rid, M1)
        before = snapshot(self.rid)
        self.error(close(self.token, self.rid, 0), 409, "REPORT_LOCKED")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_assignee_set_with_status_still_submitted_is_locked(self):
        psql("UPDATE reports SET assignee_id='%s' WHERE id='%s'" % (M1, self.rid))
        before = snapshot(self.rid)
        self.error(close(self.token, self.rid, 0), 409, "REPORT_LOCKED")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_non_submitted_status_without_assignee_is_locked(self):
        for status in ("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED", "CLOSED"):
            rid = seed(self.actor, "S", "D")
            psql("UPDATE reports SET status='%s' WHERE id='%s'" % (status, rid))
            before = snapshot(rid)
            with self.subTest(status=status):
                self.error(close(self.token, rid, 0), 409, "REPORT_LOCKED")
                self.assertEqual(snapshot(rid), before)

    def test_rpt10_lock_is_checked_before_version(self):
        assign_sql(self.rid, M1)
        self.error(close(self.token, self.rid, 99), 409, "REPORT_LOCKED")

    # ---- version
    def test_rpt10_stale_version_is_409_version_conflict_and_nothing_changes(self):
        before = snapshot(self.rid)
        for version in (1, 5, 10 ** 9):
            with self.subTest(version=version):
                self.error(close(self.token, self.rid, version), 409, "VERSION_CONFLICT")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_close_after_an_edit_needs_the_new_version(self):
        self.assertEqual(edit(self.token, self.rid, "A", "B", 0)[0], 200)
        self.error(close(self.token, self.rid, 0), 409, "VERSION_CONFLICT")
        st, _, payload = close(self.token, self.rid, 1)
        self.assertEqual(st, 200, payload[:200])
        doc = json.loads(payload)
        self.assertEqual((doc["title"], doc["description"], doc["status"], doc["version"]), ("A", "B", "CLOSED", 2))

    def test_rpt10_conflict_and_locked_messages_differ(self):
        conflict = close(self.token, self.rid, 7)
        assign_sql(self.rid, M1)
        locked = close(self.token, self.rid, 0)
        self.assertEqual(conflict[0], 409, conflict[2][:200])
        self.assertEqual(locked[0], 409, locked[2][:200])
        self.assertNotEqual(json.loads(conflict[2])["message"], json.loads(locked[2])["message"])

    # ---- body and query rules
    def test_rpt10_missing_non_integer_or_negative_version_is_400(self):
        before = snapshot(self.rid)
        for body in ({}, {"version": "0"}, {"version": 1.5}, {"version": None}, {"version": True},
                     {"version": [0]}, {"version": {"v": 0}}, {"version": -1}, {"version": -1000}):
            with self.subTest(body=body):
                self.invalid(self.post_body(body))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_extra_keys_rejected(self):
        before = snapshot(self.rid)
        for key in ("id", "reporterId", "status", "assigneeId", "title", "description", "createdAt", "updatedAt",
                    "extra"):
            with self.subTest(key=key):
                self.invalid(self.post_body({"version": 0, key: "x"}))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_non_object_or_invalid_json_body_is_400(self):
        before = snapshot(self.rid)
        for raw in (b"[]", b'"x"', b"5", b"null", b"true", b"{bad", b"", b'{"version":0}x'):
            with self.subTest(raw=raw):
                self.invalid(self.post_raw(raw))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_any_query_parameter_is_400(self):
        before = snapshot(self.rid)
        for query in ("?version=0", "?reporterId=" + OTHER[1], "?x=1", "?a=1&a=2"):
            with self.subTest(query=query):
                self.invalid(request("POST", PATH + "/" + self.rid + "/close" + query, body={"version": 0},
                                     token=self.token))
        self.assertEqual(snapshot(self.rid), before)

    # ---- 404 equivalence and isolation
    def test_rpt10_other_missing_malformed_ids_are_404_identical_to_read_404(self):
        other_rid = seed(OTHER[1], "OtherTitle", "OtherBody")
        before = snapshot(other_rid)
        cases = {"other": other_rid, "missing": str(uuid.uuid4()), "malformed": "not-a-uuid",
                 "upper": other_rid.upper()}
        reads = {k: normalise_404(request("GET", PATH + "/" + v, token=self.token)) for k, v in cases.items()}
        for k, v in cases.items():
            with self.subTest(case=k):
                res = close(self.token, v, 0)
                self.error(res, 404, "REPORT_NOT_FOUND", forbidden=("OtherTitle", "OtherBody"))
                self.assertEqual(normalise_404(res), reads[k])
                self.assertEqual(normalise_404(res), reads["missing"])
        self.assertEqual(snapshot(other_rid), before)

    def test_rpt10_other_reporters_locked_or_stale_report_is_404_not_409(self):
        other_rid = seed(OTHER[1], "O", "D")
        assign_sql(other_rid, M1)
        closed = seed(OTHER[1], "C", "D")
        psql("UPDATE reports SET status='CLOSED' WHERE id='%s'" % closed)
        before = (snapshot(other_rid), snapshot(closed))
        for rid in (other_rid, closed):
            for version in (0, 9):
                self.error(close(self.token, rid, version), 404, "REPORT_NOT_FOUND")
        self.assertEqual((snapshot(other_rid), snapshot(closed)), before)

    def test_rpt10_owner_in_body_or_headers_never_selects_the_actor(self):
        other_rid = seed(OTHER[1], "O", "D")
        before = snapshot(other_rid)
        self.invalid(self.post_body({"version": 0, "reporterId": OTHER[1]}, rid=other_rid))
        res = request("POST", PATH + "/" + other_rid + "/close", token=self.token, body={"version": 0},
                      headers={"X-Reporter-Id": OTHER[1]})
        self.assertEqual(res[0], 404)
        self.assertEqual(snapshot(other_rid), before)

    # ---- check order
    def test_rpt10_check_order_session_then_validation_then_report(self):
        missing = str(uuid.uuid4())
        self.assertEqual(request("POST", PATH + "/" + missing + "/close", body={"version": -1})[0], 401)
        self.assertEqual(request("POST", PATH + "/" + self.rid + "/close", body={"version": -1}, token="bogus")[0],
                         401)
        self.assertEqual(request("POST", PATH + "/" + self.rid + "/close", body={"version": -1},
                                 token=ENV["MP4_TEST_TOKEN_D1"])[0], 403)
        other_rid = seed(OTHER[1], "O", "D")
        locked = seed(self.actor, "L", "D")
        assign_sql(locked, M1)
        for rid in (missing, "not-a-uuid", other_rid, locked):
            with self.subTest(rid=rid):
                self.invalid(self.post_body({"version": -1}, rid=rid))
        self.assertEqual(self.post_body({"version": 0}, rid=missing)[0], 404)
        self.assertEqual(code_of(self.post_body({"version": 5}, rid=locked)), "REPORT_LOCKED")
        self.assertEqual(code_of(self.post_body({"version": 5})), "VERSION_CONFLICT")

    # ---- access
    def test_rpt10_no_session_invalid_session_inactive_and_wrong_roles(self):
        before = snapshot(self.rid)
        cases = [(None, 401, "UNAUTHENTICATED"), ("bogus", 401, "UNAUTHENTICATED"),
                 (ENV["MP4_TEST_TOKEN_R3"], 403, "FORBIDDEN"), (ENV["MP4_TEST_TOKEN_D1"], 403, "FORBIDDEN"),
                 (ENV["MP4_TEST_TOKEN_M1"], 403, "FORBIDDEN")]
        for tok, status, code in cases:
            with self.subTest(token=tok):
                self.error(close(tok, self.rid, 0), status, code, forbidden=("Original title",))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt10_every_close_response_is_no_store(self):
        locked = seed(self.actor, "L", "D")
        assign_sql(locked, M1)
        results = [close(self.token, self.rid, 3), close(self.token, locked, 0),
                   close(self.token, str(uuid.uuid4()), 0), close(None, self.rid, 0),
                   self.post_raw(b"{"), close(self.token, self.rid, 0)]
        for res in results:
            self.assertEqual(res[1].get("cache-control"), "no-store")
        self.assertEqual(results[-1][0], 200)

    # ---- methods
    def test_rpt10_allow_headers_per_path(self):
        rid = seed(self.actor, "M", "D")
        cases = [(PATH, ("PUT", "PATCH", "DELETE"), "GET, POST"),
                 (PATH + "/" + rid, ("POST", "PATCH", "DELETE"), "GET, PUT"),
                 (PATH + "/" + rid + "/close", ("GET", "PUT", "PATCH", "DELETE"), "POST")]
        before = snapshot(rid)
        for path, methods, allow in cases:
            for method in methods:
                with self.subTest(method=method, path=path):
                    res = request(method, path, body={"version": 0}, token=self.token)
                    self.error(res, 405, "METHOD_NOT_ALLOWED")
                    self.assertEqual(res[1].get("allow"), allow)
        self.assertEqual(snapshot(rid), before)

    # ---- rate limit independence
    def test_rpt10_closing_does_not_free_a_create_slot(self):
        token, actor = fresh_reporter()
        ids = []
        for _ in range(3):
            st, _, payload = create(token)
            self.assertEqual(st, 201, payload[:200])
            ids.append(json.loads(payload)["id"])
        self.assertEqual(close(token, ids[0], 0)[0], 200)
        res = create(token)
        self.assertEqual(res[0], 429, res[2][:200])
        self.assertEqual(code_of(res), "RATE_LIMITED")
        self.assertEqual(count(actor), 3)

    def test_rpt10_close_succeeds_at_the_create_limit_and_does_not_change_the_create_count(self):
        token, actor = fresh_reporter()
        ids = [seed(actor, "R%d" % i, "D", age_seconds=5) for i in range(3)]
        self.assertEqual(create(token)[0], 429)
        for rid in ids:
            res = close(token, rid, 0)
            self.assertEqual(res[0], 200, res[2][:200])
        self.assertEqual(count(actor), 3)
        self.assertEqual(create(token)[0], 429)

    # ---- races
    def test_rpt10_close_waits_for_a_committed_assignment_and_loses(self):
        for attempt in range(3):
            rid = seed(self.actor, "Orig-%d" % attempt, "OrigDesc")
            with self.subTest(attempt=attempt):
                t = assign_holding_lock(rid, M1)
                res = close(self.token, rid, 0)
                t.join(timeout=30)
                self.assertEqual(res[0], 409, res[2][:200])
                self.assertEqual(code_of(res), "REPORT_LOCKED")
                row = json.loads(snapshot(rid))
                self.assertEqual((row["title"], row["description"], row["version"], row["status"], row["assignee_id"]),
                                 ("Orig-%d" % attempt, "OrigDesc", 0, "ASSIGNED", M1))

    def test_rpt10_parallel_close_and_assignment_leave_exactly_one_outcome(self):
        # The assignment is unconditional SQL, so the final assignee is always M1. What must hold is that the
        # close result and the stored state agree: 409 means the assignment landed first (ASSIGNED, version 0);
        # 200 means the close landed first (version 1). Never 200 with version 0, never 409 with version 1.
        for attempt, delay in enumerate((0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4)):
            rid = seed(self.actor, "Orig", "OrigDesc")
            barrier = threading.Barrier(2)
            box = {}

            def do_close():
                barrier.wait(timeout=10)
                time.sleep(delay)
                box["close"] = close(self.token, rid, 0)

            def do_assign():
                barrier.wait(timeout=10)
                assign_sql(rid, M1)

            ts = [threading.Thread(target=do_close), threading.Thread(target=do_assign)]
            for t in ts:
                t.start()
            for t in ts:
                t.join(timeout=30)
            res = box["close"]
            row = json.loads(snapshot(rid))
            with self.subTest(attempt=attempt, delay=delay):
                self.assertEqual(row["assignee_id"], M1)
                self.assertEqual((row["title"], row["description"]), ("Orig", "OrigDesc"))
                if res[0] == 200:
                    self.assertEqual(row["version"], 1)
                else:
                    self.assertEqual(res[0], 409, res[2][:200])
                    self.assertEqual(code_of(res), "REPORT_LOCKED")
                    self.assertEqual((row["status"], row["version"]), ("ASSIGNED", 0))


if __name__ == "__main__":
    unittest.main()
