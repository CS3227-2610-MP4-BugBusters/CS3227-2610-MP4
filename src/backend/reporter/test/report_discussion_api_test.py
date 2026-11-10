"""Red black-box tests for report-discussion task 2.2: Reporter endpoint /api/reporter/reports/{id}/comments.

Covers DSC-01, DSC-02, DSC-04 against workflow/reporter/report-discussion-api-contract.md (revision 1).
Not covered here: DSC-03 body validation details (20,000 boundary etc.) -> task 2.3; DSC-05 rate limit -> task 2.3/2.x.
Each test takes its own pool Reporter and posts fewer than 10 comments, so the (later) rate limit is never reached.
Large threads are seeded straight into report_comments (explicit created_at) to avoid the rate limit.
Open question (not asserted): author.displayName value. The contract says it is read from the auth accounts
table, which the reporter test DB does not have, so only the key's presence is asserted."""
import json
import os
import unittest
import uuid

from reporter_api_support import ISO, UUID_RE, ApiCase, fresh_reporter, psql, request
from reporter_report_fixtures import PATH, edit, normalise_404, seed, snapshot

ENV = os.environ
D1 = ENV["MP4_TEST_ID_D1"]
INVALID = ("VALIDATION_FAILED", "The request is invalid.")
ITEM_KEYS = ["author", "body", "createdAt", "id"]
AUTHOR_KEYS = ["displayName", "id", "role"]


def thread(rid):
    return PATH + "/" + rid + "/comments"


def seed_comment(rid, author_id, role, body, created_iso, cid=None):
    cid = cid or str(uuid.uuid4())
    psql("INSERT INTO report_comments (id,report_id,author_id,author_role,text,created_at) "
         "VALUES ('%s','%s','%s','%s','%s','%s')" % (cid, rid, author_id, role, body, created_iso))
    return cid


def comment_count(rid):
    return int(psql("SELECT count(*) FROM report_comments WHERE report_id='%s'" % rid))


def doc_of(res):
    return json.loads(res[2])


class DiscussionTest(ApiCase):
    def setUp(self):
        self.token, self.actor = fresh_reporter()
        self.rid = seed(self.actor, "Discussion title", "Discussion description")

    def post(self, body, rid=None, token=None, **kw):
        return request("POST", thread(rid or self.rid), body=body, token=token or self.token, **kw)

    def get(self, rid=None, query="", token=None):
        return request("GET", thread(rid or self.rid) + query, token=token or self.token)

    def error(self, res, status, code, forbidden=()):
        self.assert_envelope(res, status, code, json.loads(res[2])["message"], forbidden=forbidden)

    def assert_item(self, item):
        self.assertEqual(sorted(item), ITEM_KEYS)
        self.assertRegex(item["id"], UUID_RE)
        self.assertRegex(item["createdAt"], ISO)
        self.assertEqual(sorted(item["author"]), AUTHOR_KEYS)
        self.assertRegex(item["author"]["id"], UUID_RE)

    # ---- DSC-01: post and read as the owner (contract: POST/GET ...comments)
    def test_dsc01_owner_posts_comment_201_with_shape_and_location(self):
        text = "  # Hi **there**\r\n<script>x</script>  "
        st, h, payload = self.post({"body": text})
        self.assertEqual(st, 201, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        self.assertEqual(h.get("location"), thread(self.rid))
        item = json.loads(payload)
        self.assert_item(item)
        self.assertEqual(item["body"], text)
        self.assertEqual(item["author"]["id"], self.actor)
        self.assertEqual(item["author"]["role"], "REPORTER")
        self.assertEqual(comment_count(self.rid), 1)

    def test_dsc01_owner_reads_posted_comments_in_order_with_envelope(self):
        first = doc_of(self.post({"body": "one"}))
        second = doc_of(self.post({"body": "two"}))
        st, h, payload = self.get()
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["items", "page", "pageSize", "totalItems"])
        self.assertEqual((doc["page"], doc["pageSize"], doc["totalItems"]), (1, 20, 2))
        self.assertEqual([i["id"] for i in doc["items"]], [first["id"], second["id"]])
        self.assertEqual([i["body"] for i in doc["items"]], ["one", "two"])
        for item in doc["items"]:
            self.assert_item(item)
        self.assertEqual(doc["items"][0], first)

    def test_dsc01_empty_thread_returns_empty_items_and_zero_total(self):
        self.assertEqual(doc_of(self.get()), {"items": [], "page": 1, "pageSize": 20, "totalItems": 0})

    def test_dsc01_items_include_other_participants_with_their_stored_role(self):
        seed_comment(self.rid, D1, "DEVELOPER", "dev says", "2026-11-10T05:00:00Z")
        item = doc_of(self.get())["items"][0]
        self.assert_item(item)
        self.assertEqual((item["author"]["id"], item["author"]["role"], item["body"]), (D1, "DEVELOPER", "dev says"))
        self.assertEqual(item["createdAt"], "2026-11-10T05:00:00.000Z")

    def test_dsc01_order_is_created_at_then_id_ascending(self):
        low, high = "00000000-0000-4000-8000-000000000001", "ffffffff-0000-4000-8000-000000000001"
        seed_comment(self.rid, D1, "DEVELOPER", "late", "2026-11-10T06:00:00Z", cid=str(uuid.uuid4()))
        seed_comment(self.rid, D1, "DEVELOPER", "tie-high", "2026-11-10T05:00:00Z", cid=high)
        seed_comment(self.rid, D1, "DEVELOPER", "early", "2026-11-10T04:00:00Z", cid=str(uuid.uuid4()))
        seed_comment(self.rid, D1, "DEVELOPER", "tie-low", "2026-11-10T05:00:00Z", cid=low)
        self.assertEqual([i["body"] for i in doc_of(self.get())["items"]], ["early", "tie-low", "tie-high", "late"])

    def test_dsc01_pagination_defaults_pages_and_past_the_end(self):
        for n in range(25):
            seed_comment(self.rid, D1, "DEVELOPER", "c%02d" % n, "2026-11-10T05:%02d:00Z" % n)
        d1 = doc_of(self.get())
        self.assertEqual((d1["page"], d1["pageSize"], d1["totalItems"], len(d1["items"])), (1, 20, 25, 20))
        self.assertEqual(d1["items"][0]["body"], "c00")
        d2 = doc_of(self.get(query="?page=2"))
        self.assertEqual((d2["page"], d2["pageSize"], d2["totalItems"], len(d2["items"])), (2, 20, 25, 5))
        self.assertEqual([i["body"] for i in d2["items"]], ["c20", "c21", "c22", "c23", "c24"])
        d3 = doc_of(self.get(query="?page=3"))
        self.assertEqual((d3["items"], d3["page"], d3["totalItems"]), ([], 3, 25))
        small = doc_of(self.get(query="?page=2&pageSize=10"))
        self.assertEqual((small["page"], small["pageSize"], len(small["items"])), (2, 10, 10))
        self.assertEqual(small["items"][0]["body"], "c10")
        big = doc_of(self.get(query="?pageSize=100"))
        self.assertEqual((big["pageSize"], len(big["items"])), (100, 25))

    def test_dsc01_invalid_pagination_query_is_400(self):
        for query in ("?page=0", "?page=-1", "?page=1.5", "?page=abc", "?page=", "?pageSize=0", "?pageSize=101",
                      "?pageSize=x", "?page=1&page=2", "?pageSize=5&pageSize=6", "?sort=desc", "?page=1&extra=1"):
            with self.subTest(query=query):
                self.assert_envelope(self.get(query=query), 400, *INVALID)

    def test_dsc01_unknown_post_key_or_spoofed_author_is_400_and_stores_nothing(self):
        other = fresh_reporter()[1]
        for key, val in (("authorId", other), ("author", {"id": other}), ("role", "MAINTAINER"),
                         ("reportId", self.rid), ("displayName", "Mallory"), ("createdAt", "2020-01-01T00:00:00Z")):
            with self.subTest(key=key):
                res = self.post({"body": "x", key: val})
                self.assert_envelope(res, 400, *INVALID, forbidden=("Mallory",))
        self.assertEqual(comment_count(self.rid), 0)

    def test_dsc01_identity_header_never_selects_the_author(self):
        other_token, other = fresh_reporter()
        other_rid = seed(other, "Other", "Other")
        res = request("POST", thread(other_rid), body={"body": "x"}, token=self.token, headers={"X-Reporter-Id": other})
        self.assertEqual(res[0], 404)
        self.assertEqual(comment_count(other_rid), 0)
        res = request("POST", thread(self.rid), body={"body": "x"}, token=self.token, headers={"X-Reporter-Id": other})
        self.assertEqual(res[0], 201)
        self.assertEqual(doc_of(res)["author"]["id"], self.actor)

    # ---- DSC-01 / DSC-04: comments never change the report
    def test_dsc04_posting_leaves_report_row_unchanged(self):
        before = snapshot(self.rid)
        for text in ("a", "b", "c"):
            self.assertEqual(self.post({"body": text})[0], 201)
        self.assertEqual(snapshot(self.rid), before)  # version, updated_at, status, assignee, title, description
        self.assertEqual(psql("SELECT version FROM reports WHERE id='%s'" % self.rid), "0")

    def test_dsc04_reporter_edit_with_old_version_still_succeeds_after_comment(self):
        self.assertEqual(self.post({"body": "note"})[0], 201)
        st, _, payload = edit(self.token, self.rid, "Edited", "Edited", 0)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(json.loads(payload)["version"], 1)
        self.assertEqual(self.get()[0], 200)
        self.assertEqual(comment_count(self.rid), 1)

    def test_dsc04_comment_on_closed_report_succeeds(self):
        psql("UPDATE reports SET status='CLOSED' WHERE id='%s'" % self.rid)
        before = snapshot(self.rid)
        res = self.post({"body": "after close"})
        self.assertEqual(res[0], 201, res[2][:200])
        self.assertEqual(snapshot(self.rid), before)
        self.assertEqual(doc_of(self.get())["totalItems"], 1)

    # ---- RPT-05 404 (contract "When the report can't be seen")
    def test_dsc01_other_missing_and_malformed_ids_are_404_identical_to_rpt05_read(self):
        other_token, other = fresh_reporter()
        other_rid = seed(other, "OtherTitle", "OtherBody")
        seed_comment(other_rid, D1, "DEVELOPER", "OtherSecretComment", "2026-11-10T05:00:00Z")
        cases = {"other": other_rid, "missing": str(uuid.uuid4()), "malformed": "not-a-uuid", "upper": other_rid.upper()}
        for name, rid in cases.items():
            read = normalise_404(request("GET", PATH + "/" + rid, token=self.token))
            self.assertEqual(read[0], 404)
            for label, res in (("GET", self.get(rid=rid)), ("POST", self.post({"body": "x"}, rid=rid))):
                with self.subTest(case=name, method=label):
                    self.error(res, 404, "REPORT_NOT_FOUND", forbidden=("OtherTitle", "OtherBody", "OtherSecretComment"))
                    self.assertEqual(normalise_404(res), read)
        self.assertEqual(comment_count(other_rid), 1)

    def test_dsc01_paths_below_comments_return_404(self):
        for suffix in ("/" + str(uuid.uuid4()), "/x", "/"):
            with self.subTest(suffix=suffix):
                for method in ("GET", "POST"):
                    res = request(method, thread(self.rid) + suffix, body={"body": "x"} if method == "POST" else None,
                                  token=self.token)
                    self.assertEqual(res[0], 404, res[2][:200])

    # ---- check order: session, validation, report visibility
    def test_dsc01_check_order_session_then_validation_then_visibility(self):
        missing = str(uuid.uuid4())
        self.assertEqual(request("POST", thread(missing), body={"body": "x"})[0], 401)
        self.assertEqual(request("POST", thread(missing), body={"body": "x"}, token="bogus")[0], 401)
        for rid in (missing, "not-a-uuid"):
            with self.subTest(rid=rid):
                self.assert_envelope(self.post({"body": "x", "authorId": D1}, rid=rid), 400, *INVALID)
                self.assert_envelope(self.get(rid=rid, query="?page=0"), 400, *INVALID)
        self.assertEqual(self.post({"body": "x"}, rid=missing)[0], 404)

    # ---- 401 / 403
    def test_dsc01_no_session_invalid_session_inactive_and_wrong_roles(self):
        cases = [(None, 401, "UNAUTHENTICATED"), ("bogus", 401, "UNAUTHENTICATED"),
                 (ENV["MP4_TEST_TOKEN_R3"], 403, "FORBIDDEN"), (ENV["MP4_TEST_TOKEN_D1"], 403, "FORBIDDEN"),
                 (ENV["MP4_TEST_TOKEN_M1"], 403, "FORBIDDEN")]
        seed_comment(self.rid, D1, "DEVELOPER", "PrivateComment", "2026-11-10T05:00:00Z")
        for tok, status, code in cases:
            for method in ("GET", "POST"):
                with self.subTest(token=tok, method=method):
                    res = request(method, thread(self.rid), body={"body": "SecretPost"} if method == "POST" else None,
                                  token=tok)
                    self.error(res, status, code, forbidden=("SecretPost", "PrivateComment", "Discussion title"))
        self.assertEqual(comment_count(self.rid), 1)

    # ---- 405
    def test_dsc01_put_patch_delete_are_405_with_allow_get_post(self):
        for method in ("PUT", "PATCH", "DELETE"):
            with self.subTest(method=method):
                res = request(method, thread(self.rid), body={"body": "x"}, token=self.token)
                self.error(res, 405, "METHOD_NOT_ALLOWED")
                self.assertEqual(res[1].get("allow"), "GET, POST")
        self.assertEqual(comment_count(self.rid), 0)


if __name__ == "__main__":
    unittest.main()
