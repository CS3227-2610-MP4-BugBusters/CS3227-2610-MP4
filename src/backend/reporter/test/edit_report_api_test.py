"""RPT-09 black-box tests for PUT /api/reporter/reports/{id} (task 2.10).

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
from reporter_report_fixtures import PATH, assign_holding_lock, assign_sql, edit, normalise_404, seed, snapshot

ENV = os.environ
INVALID = ("VALIDATION_FAILED", "The request is invalid.")
M1 = ENV["MP4_TEST_ID_M1"]
OWNER = OTHER = None


def setUpModule():
    global OWNER, OTHER
    OWNER = fresh_reporter()
    OTHER = fresh_reporter()


def hexed(report_id, column):
    return psql("SELECT encode(convert_to(%s,'UTF8'),'hex') FROM reports WHERE id='%s'" % (column, report_id))


def code_of(res):
    return json.loads(res[2])["code"]


class EditTest(ApiCase):
    def setUp(self):
        self.token, self.actor = OWNER
        self.rid = seed(self.actor, "Original title", "Original description")

    def error(self, res, status, code, forbidden=()):
        self.assert_envelope(res, status, code, json.loads(res[2])["message"], forbidden=forbidden)

    def invalid(self, res):
        self.assert_envelope(res, 400, *INVALID)

    def put_raw(self, raw, rid=None, token=None):
        return request("PUT", PATH + "/" + (rid or self.rid), raw=raw, token=token or self.token)

    def put_body(self, body, rid=None):
        return request("PUT", PATH + "/" + (rid or self.rid), body=body, token=self.token)

    # ---- success
    def test_rpt09_edit_unassigned_returns_200_detail_with_version_plus_one_and_later_updated_at(self):
        created = json.loads(request("GET", PATH + "/" + self.rid, token=self.token)[2])
        st, h, payload = edit(self.token, self.rid, "New title", "New **body**", 0)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["createdAt", "description", "id", "status", "title", "updatedAt", "version"])
        self.assertEqual((doc["id"], doc["title"], doc["description"], doc["status"], doc["version"]),
                         (self.rid, "New title", "New **body**", "SUBMITTED", 1))
        self.assertEqual(doc["createdAt"], created["createdAt"])
        self.assertGreater(doc["updatedAt"], created["updatedAt"])
        again = json.loads(request("GET", PATH + "/" + self.rid, token=self.token)[2])
        self.assertEqual(again, doc)
        self.assertEqual(psql("SELECT version FROM reports WHERE id='%s'" % self.rid), "1")

    def test_rpt09_text_is_stored_exactly_without_trimming_or_normalising(self):
        title = "  Café \U0001F600 <b>x</b>  "
        desc = "\n  # H\r\n\ttab <script>alert(1)</script>    [l](javascript:x)\n\n"
        st, _, payload = edit(self.token, self.rid, title, desc, 0)
        self.assertEqual(st, 200, payload[:200])
        doc = json.loads(payload)
        self.assertEqual((doc["title"], doc["description"]), (title, desc))
        self.assertEqual(hexed(self.rid, "title"), title.encode("utf-8").hex())
        self.assertEqual(hexed(self.rid, "description"), desc.encode("utf-8").hex())

    def test_rpt09_successive_edits_need_the_new_version(self):
        self.assertEqual(edit(self.token, self.rid, "A", "B", 0)[0], 200)
        self.assertEqual(edit(self.token, self.rid, "C", "D", 0)[0], 409)
        st, _, payload = edit(self.token, self.rid, "C", "D", 1)
        self.assertEqual(st, 200)
        self.assertEqual(json.loads(payload)["version"], 2)

    def test_rpt09_edit_without_changing_text_still_bumps_version(self):
        st, _, payload = edit(self.token, self.rid, "Original title", "Original description", 0)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(json.loads(payload)["version"], 1)

    # ---- RPT-02 boundaries on edit
    def test_rpt09_title_boundary_200_ok_201_rejected(self):
        self.assertEqual(edit(self.token, self.rid, "a" * 200, "D", 0)[0], 200)
        before = snapshot(self.rid)
        self.invalid(edit(self.token, self.rid, "a" * 201, "D", 1))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_title_length_counts_utf16_code_units(self):
        self.assertEqual(edit(self.token, self.rid, "\U0001F600" * 100, "D", 0)[0], 200)
        before = snapshot(self.rid)
        self.invalid(edit(self.token, self.rid, "\U0001F600" * 100 + "a", "D", 1))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_description_boundary_20000_ok_20001_rejected(self):
        self.assertEqual(edit(self.token, self.rid, "T", "d" * 20000, 0)[0], 200)
        before = snapshot(self.rid)
        self.invalid(edit(self.token, self.rid, "T", "d" * 20001, 1))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_nul_rejected_in_either_field(self):
        before = snapshot(self.rid)
        for title, desc in (("a\u0000b", "D"), ("T", "a\u0000b")):
            with self.subTest(title=title, desc=desc):
                self.invalid(edit(self.token, self.rid, title, desc, 0))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_lone_surrogate_rejected_in_either_field_but_pair_accepted(self):
        before = snapshot(self.rid)
        for raw in (b'{"title":"\\ud800","description":"D","version":0}',
                    b'{"title":"T","description":"x\\udc00y","version":0}',
                    b'{"title":"T","description":"\\ud83dx","version":0}'):
            with self.subTest(raw=raw):
                self.invalid(self.put_raw(raw))
        self.assertEqual(snapshot(self.rid), before)
        st, _, payload = self.put_raw(b'{"title":"\\ud83d\\ude00","description":"D","version":0}')
        self.assertEqual(st, 200, payload[:200])

    def test_rpt09_title_line_breaks_rejected_description_line_breaks_allowed(self):
        before = snapshot(self.rid)
        for title in ("a\nb", "a\rb", "a\r\nb"):
            with self.subTest(title=title):
                self.invalid(edit(self.token, self.rid, title, "D", 0))
        self.assertEqual(snapshot(self.rid), before)
        self.assertEqual(edit(self.token, self.rid, "T", "line1\nline2\r\nline3", 0)[0], 200)

    def test_rpt09_blank_or_whitespace_only_fields_rejected(self):
        before = snapshot(self.rid)
        for blank in ("", " ", "\t", " ", " ", " \t  "):
            for title, desc in ((blank, "D"), ("T", blank)):
                with self.subTest(title=title, desc=desc):
                    self.invalid(edit(self.token, self.rid, title, desc, 0))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_non_string_or_missing_fields_rejected(self):
        before = snapshot(self.rid)
        bodies = [{"title": None, "description": "D", "version": 0}, {"title": "T", "description": None, "version": 0},
                  {"title": 5, "description": "D", "version": 0}, {"title": "T", "description": ["x"], "version": 0},
                  {"description": "D", "version": 0}, {"title": "T", "version": 0}]
        for body in bodies:
            with self.subTest(body=body):
                self.invalid(self.put_body(body))
        self.assertEqual(snapshot(self.rid), before)

    # ---- version, keys, body shape
    def test_rpt09_missing_non_integer_or_negative_version_is_400(self):
        before = snapshot(self.rid)
        for version in ("<missing>", "1", 1.5, None, True, [0], {"v": 0}, -1, -1000):
            body = {"title": "T", "description": "D"}
            if version != "<missing>":
                body["version"] = version
            with self.subTest(version=version):
                self.invalid(self.put_body(body))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_extra_keys_rejected(self):
        before = snapshot(self.rid)
        for key in ("id", "reporterId", "status", "assigneeId", "createdAt", "updatedAt", "extra", "steps"):
            with self.subTest(key=key):
                self.invalid(self.put_body({"title": "T", "description": "D", "version": 0, key: "x"}))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_non_object_or_invalid_json_body_is_400(self):
        before = snapshot(self.rid)
        for raw in (b"[]", b'"x"', b"5", b"null", b"true", b"{bad", b"",
                    b'{"title":"T","description":"D","version":0}x'):
            with self.subTest(raw=raw):
                self.invalid(self.put_raw(raw))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_validation_error_body_does_not_echo_submitted_text(self):
        self.assert_envelope(edit(self.token, self.rid, "", "EchoCanary", 0), 400, *INVALID,
                             forbidden=("EchoCanary",))

    # ---- locks and conflicts
    def test_rpt09_assigned_report_is_locked_and_unchanged(self):
        assign_sql(self.rid, M1)
        before = snapshot(self.rid)
        self.error(edit(self.token, self.rid, "T", "D", 0), 409, "REPORT_LOCKED")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_assignee_set_with_status_still_submitted_is_locked(self):
        psql("UPDATE reports SET assignee_id='%s' WHERE id='%s'" % (M1, self.rid))
        before = snapshot(self.rid)
        self.error(edit(self.token, self.rid, "T", "D", 0), 409, "REPORT_LOCKED")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_non_submitted_status_without_assignee_is_locked(self):
        for status in ("ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED", "CLOSED"):
            rid = seed(self.actor, "S", "D")
            psql("UPDATE reports SET status='%s' WHERE id='%s'" % (status, rid))
            before = snapshot(rid)
            with self.subTest(status=status):
                self.error(edit(self.token, rid, "T", "D", 0), 409, "REPORT_LOCKED")
                self.assertEqual(snapshot(rid), before)

    def test_rpt09_lock_is_checked_before_version(self):
        assign_sql(self.rid, M1)
        self.error(edit(self.token, self.rid, "T", "D", 99), 409, "REPORT_LOCKED")

    def test_rpt09_stale_version_is_409_version_conflict_and_nothing_changes(self):
        before = snapshot(self.rid)
        for version in (1, 5, 10 ** 9):
            with self.subTest(version=version):
                self.error(edit(self.token, self.rid, "T", "D", version), 409, "VERSION_CONFLICT")
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_conflict_and_locked_messages_differ_and_never_echo_text(self):
        conflict = edit(self.token, self.rid, "EchoTitle", "EchoDesc", 7)
        assign_sql(self.rid, M1)
        locked = edit(self.token, self.rid, "EchoTitle", "EchoDesc", 0)
        self.assertNotEqual(json.loads(conflict[2])["message"], json.loads(locked[2])["message"])
        for res in (conflict, locked):
            self.assertNotIn(b"EchoTitle", res[2])
            self.assertNotIn(b"EchoDesc", res[2])

    # ---- 404 equivalence and isolation
    def test_rpt09_other_missing_malformed_ids_are_404_identical_to_read_404(self):
        other_rid = seed(OTHER[1], "OtherTitle", "OtherBody")
        before = snapshot(other_rid)
        cases = {"other": other_rid, "missing": str(uuid.uuid4()), "malformed": "not-a-uuid",
                 "upper": other_rid.upper()}
        reads = {k: normalise_404(request("GET", PATH + "/" + v, token=self.token)) for k, v in cases.items()}
        for k, v in cases.items():
            with self.subTest(case=k):
                res = edit(self.token, v, "T", "D", 0)
                self.error(res, 404, "REPORT_NOT_FOUND", forbidden=("OtherTitle", "OtherBody"))
                self.assertEqual(normalise_404(res), reads[k])
                self.assertEqual(normalise_404(res), reads["missing"])
        self.assertEqual(snapshot(other_rid), before)

    def test_rpt09_other_reporters_locked_or_stale_report_is_404_not_409(self):
        other_rid = seed(OTHER[1], "O", "D")
        assign_sql(other_rid, M1)
        before = snapshot(other_rid)
        for version in (0, 9):
            self.error(edit(self.token, other_rid, "T", "D", version), 404, "REPORT_NOT_FOUND")
        self.assertEqual(snapshot(other_rid), before)

    def test_rpt09_owner_in_body_or_headers_never_selects_the_actor(self):
        other_rid = seed(OTHER[1], "O", "D")
        before = snapshot(other_rid)
        self.invalid(self.put_body({"title": "T", "description": "D", "version": 0, "reporterId": OTHER[1]},
                                   rid=other_rid))
        res = request("PUT", PATH + "/" + other_rid, token=self.token,
                      body={"title": "T", "description": "D", "version": 0}, headers={"X-Reporter-Id": OTHER[1]})
        self.assertEqual(res[0], 404)
        self.assertEqual(snapshot(other_rid), before)

    # ---- check order
    def test_rpt09_check_order_session_then_validation_then_report(self):
        missing = str(uuid.uuid4())
        bad = {"title": "", "description": "D", "version": 0}
        good = {"title": "T", "description": "D", "version": 0}
        self.assertEqual(request("PUT", PATH + "/" + missing, body=bad)[0], 401)
        self.assertEqual(request("PUT", PATH + "/" + self.rid, body=bad, token="bogus")[0], 401)
        self.assertEqual(request("PUT", PATH + "/" + self.rid, body=bad, token=ENV["MP4_TEST_TOKEN_D1"])[0], 403)
        other_rid = seed(OTHER[1], "O", "D")
        locked = seed(self.actor, "L", "D")
        assign_sql(locked, M1)
        for rid in (missing, "not-a-uuid", other_rid, locked):
            with self.subTest(rid=rid):
                self.invalid(self.put_body(bad, rid=rid))
        self.assertEqual(self.put_body(good, rid=missing)[0], 404)
        self.assertEqual(code_of(self.put_body({**good, "version": 5}, rid=locked)), "REPORT_LOCKED")
        self.assertEqual(code_of(self.put_body({**good, "version": 5})), "VERSION_CONFLICT")

    # ---- access
    def test_rpt09_no_session_invalid_session_inactive_and_wrong_roles(self):
        before = snapshot(self.rid)
        cases = [(None, 401, "UNAUTHENTICATED"), ("bogus", 401, "UNAUTHENTICATED"),
                 (ENV["MP4_TEST_TOKEN_R3"], 403, "FORBIDDEN"), (ENV["MP4_TEST_TOKEN_D1"], 403, "FORBIDDEN"),
                 (ENV["MP4_TEST_TOKEN_M1"], 403, "FORBIDDEN")]
        for tok, status, code in cases:
            with self.subTest(token=tok):
                self.error(edit(tok, self.rid, "SecretEdit", "SecretEdit", 0), status, code,
                           forbidden=("SecretEdit", "Original title"))
        self.assertEqual(snapshot(self.rid), before)

    def test_rpt09_every_edit_response_is_no_store(self):
        locked = seed(self.actor, "L", "D")
        assign_sql(locked, M1)
        results = [edit(self.token, self.rid, "T", "D", 3), edit(self.token, locked, "T", "D", 0),
                   edit(self.token, str(uuid.uuid4()), "T", "D", 0), edit(None, self.rid, "T", "D", 0),
                   self.put_raw(b"{"), edit(self.token, self.rid, "T", "D", 0)]
        for res in results:
            self.assertEqual(res[1].get("cache-control"), "no-store")
        self.assertEqual(results[-1][0], 200)

    # ---- rate limit independence
    def test_rpt09_edit_succeeds_at_the_create_limit_and_does_not_change_the_create_count(self):
        token, actor = fresh_reporter()
        ids = [seed(actor, "R%d" % i, "D", age_seconds=5) for i in range(3)]
        self.assertEqual(request("POST", PATH, body={"title": "T", "description": "D"}, token=token)[0], 429)
        for rid in ids:
            self.assertEqual(edit(token, rid, "Edited", "Edited", 0)[0], 200)
        self.assertEqual(count(actor), 3)
        self.assertEqual(request("POST", PATH, body={"title": "T", "description": "D"}, token=token)[0], 429)

    # ---- races
    def test_rpt09_edit_waits_for_a_committed_assignment_and_loses(self):
        for attempt in range(3):
            rid = seed(self.actor, "Orig-%d" % attempt, "OrigDesc")
            with self.subTest(attempt=attempt):
                t = assign_holding_lock(rid, M1)
                res = edit(self.token, rid, "Edited", "Edited", 0)
                t.join(timeout=30)
                self.assertEqual(res[0], 409, res[2][:200])
                self.assertEqual(code_of(res), "REPORT_LOCKED")
                row = json.loads(snapshot(rid))
                self.assertEqual((row["title"], row["description"], row["version"], row["status"], row["assignee_id"]),
                                 ("Orig-%d" % attempt, "OrigDesc", 0, "ASSIGNED", M1))

    def test_rpt09_parallel_edit_and_assignment_leave_exactly_one_outcome(self):
        # The assignment is unconditional SQL, so the final state is always assigned. What must hold is that the
        # edit result and the stored text agree: 200 means the edit landed first (edited text, version 1); 409
        # means the assignment landed first (original text, version 0). Never 200 with the old text or 409 with
        # the new text.
        for attempt, delay in enumerate((0, 0.05, 0.1, 0.15, 0.2, 0.25, 0.3, 0.4)):
            rid = seed(self.actor, "Orig", "OrigDesc")
            barrier = threading.Barrier(2)
            box = {}

            def do_edit():
                barrier.wait(timeout=10)
                time.sleep(delay)
                box["edit"] = edit(self.token, rid, "Edited", "Edited", 0)

            def do_assign():
                barrier.wait(timeout=10)
                assign_sql(rid, M1)

            ts = [threading.Thread(target=do_edit), threading.Thread(target=do_assign)]
            for t in ts:
                t.start()
            for t in ts:
                t.join(timeout=30)
            res = box["edit"]
            row = json.loads(snapshot(rid))
            with self.subTest(attempt=attempt, delay=delay):
                self.assertEqual((row["status"], row["assignee_id"]), ("ASSIGNED", M1))
                if res[0] == 200:
                    self.assertEqual((row["title"], row["description"], row["version"]), ("Edited", "Edited", 1))
                else:
                    self.assertEqual(res[0], 409, res[2][:200])
                    self.assertEqual(code_of(res), "REPORT_LOCKED")
                    self.assertEqual((row["title"], row["description"], row["version"]), ("Orig", "OrigDesc", 0))


if __name__ == "__main__":
    unittest.main()
