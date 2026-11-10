"""RPT-06 and RPT-07 black-box tests across the three Reporter endpoints (task 2.5)."""
import json
import os
import unittest
import uuid

from reporter_api_support import ApiCase, count, fresh_reporter, request
from reporter_report_fixtures import PATH, seed

ENV = os.environ
LEAKS = ("Exception", "Traceback", "\tat ", "at mp4.", "at java.", "org.postgresql", "java.lang", "SELECT ",
         "INSERT ", "reports_", "PSQL", "SQLState", "ERROR:")


def endpoints(report_id):
    """name -> callable(token, headers) for each endpoint; POST body is a valid create."""
    return {
        "create": lambda tok, h=None: request("POST", PATH, body={"title": "SecretTitle", "description": "SecretDesc"},
                                              token=tok, headers=h),
        "list": lambda tok, h=None: request("GET", PATH, token=tok, headers=h),
        "read": lambda tok, h=None: request("GET", PATH + "/" + report_id, token=tok, headers=h),
    }


class AccessTest(ApiCase):
    def safe_error(self, res, status, code):
        st, h, payload = res
        self.assertEqual(st, status, payload[:200])
        self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(h.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["code", "message", "requestId"])
        self.assertEqual(doc["code"], code)
        self.assertTrue(isinstance(doc["message"], str) and doc["message"].strip())
        self.assertTrue(isinstance(doc["requestId"], str) and doc["requestId"])
        text = payload.decode("utf-8")
        for leak in LEAKS + ("SecretTitle", "SecretDesc", "OwnTitle", "OwnBody"):
            self.assertNotIn(leak, text)
        return doc

    def setUp(self):
        self.owner = ENV["MP4_TEST_ID_R1"]
        self.rid = seed(self.owner, "OwnTitle", "OwnBody")
        self.eps = endpoints(self.rid)

    # --- RPT-06: 401
    def test_rpt06_no_session_is_401_on_every_endpoint(self):
        before = count()
        for name, call in self.eps.items():
            with self.subTest(endpoint=name):
                self.safe_error(call(None), 401, "UNAUTHENTICATED")
        self.assertEqual(count(), before)

    def test_rpt06_invalid_session_is_401_on_every_endpoint(self):
        before = count()
        for name, call in self.eps.items():
            for tok in ("not-a-real-token", "0" * 32, "Bearer"):
                with self.subTest(endpoint=name, token=tok):
                    self.safe_error(call(tok), 401, "UNAUTHENTICATED")
        for name, call in self.eps.items():
            with self.subTest(endpoint=name, scheme="basic"):
                self.safe_error(call(None, {"Authorization": "Basic " + ENV["MP4_TEST_TOKEN_R1"]}), 401, "UNAUTHENTICATED")
        self.assertEqual(count(), before)

    # --- RPT-06: 403
    def test_rpt06_inactive_developer_maintainer_are_403_on_every_endpoint(self):
        before = count()
        for who in ("R3", "D1", "M1"):
            for name, call in self.eps.items():
                with self.subTest(actor=who, endpoint=name):
                    doc = self.safe_error(call(ENV["MP4_TEST_TOKEN_" + who]), 403, "FORBIDDEN")
        self.assertEqual(count(), before)

    def test_rpt06_403_does_not_disclose_existing_report(self):
        # a Developer asking for a real report must not learn anything about it
        a = self.safe_error(self.eps["read"](ENV["MP4_TEST_TOKEN_D1"]), 403, "FORBIDDEN")
        b = self.safe_error(request("GET", PATH + "/" + str(uuid.uuid4()), token=ENV["MP4_TEST_TOKEN_D1"]), 403,
                            "FORBIDDEN")
        self.assertEqual(a["message"], b["message"])

    # --- RPT-06: identity supplied by the client is ignored
    def test_rpt06_identity_headers_do_not_authenticate_or_select_actor(self):
        r1 = ENV["MP4_TEST_ID_R1"]
        forged = {"X-Reporter-Id": r1, "X-User-Id": r1, "X-Actor-Id": r1, "X-Role": "REPORTER", "X-User-Role": "REPORTER"}
        for name, call in self.eps.items():
            with self.subTest(endpoint=name, actor="anonymous"):
                self.safe_error(call(None, forged), 401, "UNAUTHENTICATED")
            for who in ("D1", "M1", "R3"):
                with self.subTest(endpoint=name, actor=who):
                    self.safe_error(call(ENV["MP4_TEST_TOKEN_" + who], forged), 403, "FORBIDDEN")

    def test_rpt06_identity_header_cannot_read_or_list_another_reporters_data(self):
        token, mine = fresh_reporter()
        other_token, other = fresh_reporter()
        other_rid = seed(other, "OtherTitle")
        forged = {"X-Reporter-Id": other, "X-User-Id": other, "X-Actor-Id": other}
        st, _, payload = request("GET", PATH, token=token, headers=forged)
        self.assertEqual(st, 200, payload[:200])
        self.assertEqual(json.loads(payload)["items"], [])
        self.assertEqual(request("GET", PATH + "/" + other_rid, token=token, headers=forged)[0], 404)

    def test_rpt06_identity_in_body_is_rejected_and_never_selects_actor(self):
        token, mine = fresh_reporter()
        _, victim = fresh_reporter()
        for key, val in (("reporterId", victim), ("role", "REPORTER"), ("actor", victim), ("userId", victim)):
            with self.subTest(key=key):
                res = request("POST", PATH, body={"title": "T", "description": "D", key: val}, token=token)
                self.assertEqual(res[0], 400, res[2][:200])
                self.safe_error(res, 400, "VALIDATION_FAILED")
        self.assertEqual(count(mine), 0)
        self.assertEqual(count(victim), 0)

    def test_rpt06_identity_in_body_does_not_bypass_wrong_role_or_session(self):
        body = {"title": "T", "description": "D", "reporterId": ENV["MP4_TEST_ID_R1"], "role": "REPORTER"}
        before = count()
        self.safe_error(request("POST", PATH, body=body), 401, "UNAUTHENTICATED")
        self.safe_error(request("POST", PATH, body=body, token=ENV["MP4_TEST_TOKEN_D1"]), 403, "FORBIDDEN")
        self.assertEqual(count(), before)

    def test_rpt06_identity_in_query_never_selects_actor(self):
        r1 = ENV["MP4_TEST_ID_R1"]
        for name, call_path in (("list", PATH), ("read", PATH + "/" + self.rid), ("create", PATH)):
            method = "POST" if name == "create" else "GET"
            body = {"title": "SecretTitle", "description": "SecretDesc"} if name == "create" else None
            with self.subTest(endpoint=name, actor="anonymous"):
                self.safe_error(request(method, call_path + "?reporterId=" + r1 + "&role=REPORTER", body=body),
                                401, "UNAUTHENTICATED")
            with self.subTest(endpoint=name, actor="developer"):
                self.safe_error(request(method, call_path + "?reporterId=" + r1 + "&role=REPORTER", body=body,
                                        token=ENV["MP4_TEST_TOKEN_D1"]), 403, "FORBIDDEN")

    def test_rpt06_identity_in_query_on_create_never_stores_under_another_reporter(self):
        token, mine = fresh_reporter()
        _, victim = fresh_reporter()
        st, _, payload = request("POST", PATH + "?reporterId=" + victim, body={"title": "T", "description": "D"},
                                 token=token)
        self.assertIn(st, (201, 400), payload[:200])
        self.assertEqual(count(victim), 0)
        self.assertEqual(count(mine), 1 if st == 201 else 0)

    def test_rpt06_identity_in_query_on_list_is_400_and_leaks_nothing(self):
        token, _ = fresh_reporter()
        _, other = fresh_reporter()
        seed(other, "OtherTitle")
        res = request("GET", PATH + "?reporterId=" + other, token=token)
        self.safe_error(res, 400, "VALIDATION_FAILED")
        self.assertNotIn(b"OtherTitle", res[2])

    # --- order of session vs validation
    def test_rpt06_session_checked_before_validation(self):
        self.safe_error(request("POST", PATH, raw=b"{not json"), 401, "UNAUTHENTICATED")
        self.safe_error(request("POST", PATH, raw=b"{not json", token=ENV["MP4_TEST_TOKEN_D1"]), 403, "FORBIDDEN")
        self.safe_error(request("GET", PATH + "?page=0"), 401, "UNAUTHENTICATED")
        self.safe_error(request("GET", PATH + "?page=0", token=ENV["MP4_TEST_TOKEN_M1"]), 403, "FORBIDDEN")

    # --- RPT-07: safe error bodies, fixed messages, no-store
    def test_rpt07_error_bodies_have_only_code_message_request_id(self):
        token, _ = fresh_reporter()
        cases = [
            (request("POST", PATH, raw=b"{not json", token=token), 400, "VALIDATION_FAILED"),
            (request("POST", PATH, body={"title": "SecretTitle<script>", "description": ""}, token=token), 400,
             "VALIDATION_FAILED"),
            (request("GET", PATH + "?page=zzz", token=token), 400, "VALIDATION_FAILED"),
            (request("GET", PATH), 401, "UNAUTHENTICATED"),
            (request("GET", PATH, token=ENV["MP4_TEST_TOKEN_D1"]), 403, "FORBIDDEN"),
            (request("GET", PATH + "/" + str(uuid.uuid4()), token=token), 404, "REPORT_NOT_FOUND"),
            (request("GET", PATH + "/%00%27%3B%20DROP%20TABLE%20reports%3B--", token=token), 404, "REPORT_NOT_FOUND"),
        ]
        for res, status, code in cases:
            with self.subTest(code=code, status=status):
                self.safe_error(res, status, code)

    def test_rpt07_messages_are_fixed_per_code_and_do_not_echo_input(self):
        token, _ = fresh_reporter()
        v1 = self.safe_error(request("POST", PATH, body={"title": "SecretTitle", "description": " "}, token=token),
                             400, "VALIDATION_FAILED")
        v2 = self.safe_error(request("GET", PATH + "?page=SecretTitle", token=token), 400, "VALIDATION_FAILED")
        v3 = self.safe_error(request("POST", PATH, raw=b"SecretDesc", token=token), 400, "VALIDATION_FAILED")
        self.assertEqual(v1["message"], "The request is invalid.")
        self.assertEqual(v1["message"], v2["message"])
        self.assertEqual(v1["message"], v3["message"])
        n1 = self.safe_error(request("GET", PATH + "/" + str(uuid.uuid4()), token=token), 404, "REPORT_NOT_FOUND")
        n2 = self.safe_error(request("GET", PATH + "/SecretTitle", token=token), 404, "REPORT_NOT_FOUND")
        self.assertEqual(n1["message"], n2["message"])
        f1 = self.safe_error(request("GET", PATH, token=ENV["MP4_TEST_TOKEN_D1"]), 403, "FORBIDDEN")
        f2 = self.safe_error(request("POST", PATH, body={"title": "SecretTitle", "description": "SecretDesc"},
                                    token=ENV["MP4_TEST_TOKEN_R3"]), 403, "FORBIDDEN")
        self.assertEqual(f1["message"], f2["message"])
        u1 = self.safe_error(request("GET", PATH), 401, "UNAUTHENTICATED")
        u2 = self.safe_error(request("GET", PATH, token="SecretTitle"), 401, "UNAUTHENTICATED")
        self.assertEqual(u1["message"], u2["message"])
        self.assertEqual(len({v1["message"], n1["message"], f1["message"], u1["message"]}), 4)

    def test_rpt07_request_ids_differ_between_requests(self):
        a = json.loads(request("GET", PATH)[2])["requestId"]
        b = json.loads(request("GET", PATH)[2])["requestId"]
        self.assertNotEqual(a, b)

    def test_rpt07_no_store_on_success_responses(self):
        token, actor = fresh_reporter()
        st, h, payload = request("POST", PATH, body={"title": "T", "description": "D"}, token=token)
        self.assertEqual(st, 201, payload[:200])
        self.assertEqual(h.get("cache-control"), "no-store")
        rid = json.loads(payload)["id"]
        for path in (PATH, PATH + "/" + rid):
            with self.subTest(path=path):
                st, h, payload = request("GET", path, token=token)
                self.assertEqual(st, 200, payload[:200])
                self.assertEqual(h.get("cache-control"), "no-store")
                self.assertEqual(h.get("content-type"), "application/json; charset=utf-8")

    # --- other methods
    def test_contract_other_methods_are_405_method_not_allowed(self):
        token, _ = fresh_reporter()
        before = count()
        for method in ("PUT", "PATCH", "DELETE"):
            for path in (PATH, PATH + "/" + self.rid):
                if method == "PUT" and path != PATH:
                    continue  # PUT on a report is valid since contract revision 3 (task 2.10)
                with self.subTest(method=method, path=path):
                    self.safe_error(request(method, path, body={"title": "T", "description": "D"}, token=token),
                                    405, "METHOD_NOT_ALLOWED")
        for path in (PATH + "/" + self.rid,):
            with self.subTest(method="POST", path=path):
                self.safe_error(request("POST", path, body={"title": "T", "description": "D"}, token=token), 405,
                                "METHOD_NOT_ALLOWED")
        self.assertEqual(count(), before)


if __name__ == "__main__":
    unittest.main()
