"""Shared stdlib helpers for Reporter API black-box tests.

Rate-limit avoidance (RPT-08 belongs to task 2.6, not asserted here): the runner
creates a pool of 60 fresh active Reporters. Every test that needs a successful
create takes its own Reporter from fresh_reporter(), so no Reporter ever makes more
than 2 successful creates. Rejected requests never count toward the limit."""
import http.client
import json
import os
import re
import subprocess
import unittest
import urllib.parse

ORIGIN = urllib.parse.urlparse(os.environ["MP4_TEST_ORIGIN"])
ISO = re.compile(r"^\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d\.\d{3}Z$")
UUID_RE = re.compile(r"^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$")
_POOL = [p.split(":") for p in os.environ["MP4_TEST_POOL"].split(",")]
_handed_out = set()


def fresh_reporter():
    """Returns (token, uuid) of a pool Reporter with no stored reports.

    Test files run as separate processes against one database and one pool, so
    the check is made against the database, not an in-memory cursor. Entries
    already used (stored reports) or handed out in this process are skipped."""
    for token, uuid in _POOL:
        if uuid in _handed_out:
            continue
        if int(psql("SELECT count(*) FROM reports WHERE reporter_id='%s'" % uuid)) == 0:
            _handed_out.add(uuid)
            return token, uuid
    raise RuntimeError("Reporter pool exhausted: all %d pool Reporters already have reports" % len(_POOL))


def request(method, path, body=None, token=None, raw=None, headers=None):
    conn = http.client.HTTPConnection(ORIGIN.hostname, ORIGIN.port, timeout=15)
    h = dict(headers or {})
    if token:
        h["Authorization"] = "Bearer " + token
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
    if data is not None:
        h.setdefault("Content-Type", "application/json")
    conn.request(method, path, body=data, headers=h)
    r = conn.getresponse()
    payload = r.read()
    out = (r.status, {k.lower(): v for k, v in r.getheaders()}, payload)
    conn.close()
    return out


def psql(sql):
    p = subprocess.run(["docker", "exec", "-i", os.environ["MP4_TEST_PG_CONTAINER"], "psql", "-X", "-A", "-t",
                        "-U", "mp4_fixture", "-d", os.environ["MP4_TEST_DATABASE_NAME"], "-c", sql],
                       capture_output=True, check=True)
    return p.stdout.decode("utf-8").rstrip("\n")


def count(reporter_id=None):
    where = "" if reporter_id is None else " WHERE reporter_id='%s'" % reporter_id
    return int(psql("SELECT count(*) FROM reports" + where))


class ApiCase(unittest.TestCase):
    def assert_envelope(self, res, status, code, message, forbidden=()):
        st, headers, payload = res
        self.assertEqual(st, status, payload[:200])
        self.assertEqual(headers.get("content-type"), "application/json; charset=utf-8")
        self.assertEqual(headers.get("cache-control"), "no-store")
        doc = json.loads(payload)
        self.assertEqual(sorted(doc), ["code", "message", "requestId"])
        self.assertEqual(doc["code"], code)
        self.assertEqual(doc["message"], message)
        self.assertTrue(isinstance(doc["requestId"], str) and doc["requestId"])
        text = payload.decode("utf-8")
        for f in forbidden:
            self.assertNotIn(f, text)
