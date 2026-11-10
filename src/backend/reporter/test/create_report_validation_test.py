"""RPT-02 black-box tests for POST /api/reporter/reports validation (task 2.3).

Rejections use R2 (they never count toward the rate limit); each successful
boundary create takes its own fresh Reporter (see reporter_api_support)."""
import json
import os
import unittest

from reporter_api_support import ApiCase, count, fresh_reporter, request

PATH = "/api/reporter/reports"
MSG = "The request is invalid."
OK_T, OK_D = "Title", "Description"


def units(s):
    return len(s.encode("utf-16-le")) // 2


class ValidationTest(ApiCase):
    def reject(self, raw=None, body=None, echo=()):
        token, actor = os.environ["MP4_TEST_TOKEN_R2"], os.environ["MP4_TEST_ID_R2"]
        before_all, before_actor = count(), count(actor)
        res = request("POST", PATH, body=body, raw=raw, token=token)
        self.assert_envelope(res, 400, "VALIDATION_FAILED", MSG, forbidden=echo)
        self.assertEqual(count(actor), before_actor)
        self.assertEqual(count(), before_all)

    def reject_fields(self, title, description, echo=()):
        raw = None
        if isinstance(title, RawJson) or isinstance(description, RawJson):
            raw = ("{\"title\":%s,\"description\":%s}" % (enc(title), enc(description))).encode("utf-8")
            self.reject(raw=raw, echo=echo)
        else:
            self.reject(body={"title": title, "description": description}, echo=echo)

    def accept(self, title, description):
        token, actor = fresh_reporter()
        st, _, payload = request("POST", PATH, body={"title": title, "description": description}, token=token)
        self.assertEqual(st, 201, payload[:200])
        self.assertEqual(count(actor), 1)
        doc = json.loads(payload)
        self.assertEqual((doc["title"], doc["description"]), (title, description))

    # --- title boundaries
    def test_rpt02_title_200_units_accepted(self):
        self.accept("a" * 200, OK_D)

    def test_rpt02_title_201_units_rejected(self):
        self.reject_fields("a" * 201, OK_D, echo=("a" * 50,))

    def test_rpt02_title_astral_100_emoji_is_200_units_accepted(self):
        t = "\U0001F600" * 100
        self.assertEqual(units(t), 200)
        self.accept(t, OK_D)

    def test_rpt02_title_astral_101_emoji_is_202_units_rejected(self):
        self.reject_fields("\U0001F600" * 101, OK_D)

    def test_rpt02_title_199_bmp_plus_one_emoji_is_201_units_rejected(self):
        self.reject_fields("a" * 199 + "\U0001F600", OK_D)

    def test_rpt02_title_blank_variants_rejected(self):
        for t in ("", " ", "   ", "\t", "　", " ", "  ", " 　 "):
            with self.subTest(title=t):
                self.reject_fields(t, OK_D)

    def test_rpt02_title_line_breaks_rejected(self):
        for t in ("a\nb", "a\rb", "a\r\nb", "\nabc", "abc\n"):
            with self.subTest(title=t):
                self.reject_fields(t, OK_D)

    # --- description boundaries
    def test_rpt02_description_20000_units_accepted(self):
        self.accept(OK_T, "d" * 20000)

    def test_rpt02_description_20001_units_rejected(self):
        self.reject_fields(OK_T, "d" * 20001, echo=("d" * 50,))

    def test_rpt02_description_astral_boundary(self):
        ok = "\U0001F600" * 10000
        self.assertEqual(units(ok), 20000)
        self.accept(OK_T, ok)
        self.reject_fields(OK_T, "x" + ok)

    def test_rpt02_description_blank_variants_rejected(self):
        for d in ("", " ", "\n", "\r\n\t", "　", "  \n "):
            with self.subTest(description=d):
                self.reject_fields(OK_T, d)

    # --- text PostgreSQL cannot store faithfully
    def test_rpt02_nul_rejected_in_either_field(self):
        self.reject_fields("a\u0000b", OK_D)
        self.reject_fields(OK_T, "a\u0000b")
        self.reject_fields(RawJson('"a\\u0000b"'), OK_D)
        self.reject_fields(OK_T, RawJson('"a\\u0000b"'))

    def test_rpt02_unpaired_surrogate_rejected_in_either_field(self):
        for esc in ('"a\\ud800"', '"\\udc00a"', '"\\ud800x"', '"\\udbff\\udbff"', '"\\ude00\\ud83d"'):
            with self.subTest(escape=esc):
                self.reject_fields(RawJson(esc), OK_D)
                self.reject_fields(OK_T, RawJson(esc))

    def test_rpt02_valid_surrogate_pair_escape_accepted(self):
        token, actor = fresh_reporter()
        raw = b'{"title":"\\ud83d\\ude00","description":"\\ud83d\\ude00 ok"}'
        st, _, payload = request("POST", PATH, raw=raw, token=token)
        self.assertEqual(st, 201, payload[:200])
        doc = json.loads(payload)
        self.assertEqual((doc["title"], doc["description"]), ("\U0001F600", "\U0001F600 ok"))

    # --- type and body errors
    def test_rpt02_null_and_non_string_values_rejected(self):
        for v in (None, 1, 1.5, True, False, [], ["a"], {}, {"a": "b"}):
            with self.subTest(value=v):
                self.reject_fields(v, OK_D)
                self.reject_fields(OK_T, v)

    def test_rpt02_missing_field_rejected(self):
        self.reject(body={"description": OK_D})
        self.reject(body={"title": OK_T})
        self.reject(body={})

    def test_rpt02_extra_keys_rejected(self):
        extras = {"reproductionSteps": "x", "id": "7d3c0b1e-0000-4000-8000-000000000001",
                  "reporterId": "7d3c0b1e-0000-4000-8000-000000000002", "status": "SUBMITTED", "version": 0,
                  "assigneeId": None, "createdAt": "2026-11-09T05:00:00.000Z",
                  "updatedAt": "2026-11-09T05:00:00.000Z", "unknown": "x"}
        for k, v in extras.items():
            with self.subTest(key=k):
                self.reject(body={"title": OK_T, "description": OK_D, k: v})

    def test_rpt02_duplicate_key_or_case_variant_rejected(self):
        self.reject(raw=b'{"title":"a","description":"b","Title":"c"}')

    def test_rpt02_invalid_json_non_object_and_empty_body_rejected(self):
        for raw in (b"", b"   ", b"{", b"{not json}", b'{"title":"a","description":"b"', b"null", b"[]", b"1",
                    b'"str"', b"true", b'[{"title":"a","description":"b"}]', b"\xff\xfe{}",
                    b'{"title":"a","description":"b"} trailing'):
            with self.subTest(raw=raw):
                self.reject(raw=raw)

    def test_rpt02_failures_do_not_echo_submitted_text(self):
        secret = "SECRET-MARKER-é中"
        self.reject(body={"title": secret, "description": OK_D, "bogus": secret}, echo=(secret, "SECRET-MARKER"))
        self.reject(body={"title": "a\n" + secret, "description": OK_D}, echo=("SECRET-MARKER",))
        self.reject(raw=b'{"title":"' + secret.encode() + b'"', echo=("SECRET-MARKER",))

    def test_rpt02_session_checked_before_validation(self):
        before = count()
        for raw in (b"{", b'{"title":"","description":""}', b'{"id":"x"}'):
            res = request("POST", PATH, raw=raw, token=None)
            self.assertEqual(res[0], 401, res[2][:200])
            self.assertEqual(json.loads(res[2])["code"], "UNAUTHENTICATED")
            self.assertEqual(res[1].get("cache-control"), "no-store")
        self.assertEqual(count(), before)


class RawJson(str):
    """A JSON fragment inserted verbatim into the request body."""


def enc(v):
    return str(v) if isinstance(v, RawJson) else json.dumps(v, ensure_ascii=False)


if __name__ == "__main__":
    unittest.main(verbosity=2)
