"""Prints shell assignments for the synthetic actors (random UUIDs and tokens)."""
import secrets
import uuid

fixed = [("R1", "REPORTER", True), ("R2", "REPORTER", True), ("R3", "REPORTER", False),
         ("D1", "DEVELOPER", True), ("M1", "MAINTAINER", True)]
entries = []
for name, role, active in fixed:
    i, t = uuid.uuid4(), secrets.token_hex(16)
    print("MP4_TEST_ID_%s=%s\nMP4_TEST_TOKEN_%s=%s" % (name, i, name, t))
    entries.append("%s=%s:%s:%s" % (t, i, role, "true" if active else "false"))
pool = []
for _ in range(60):
    i, t = uuid.uuid4(), secrets.token_hex(16)
    entries.append("%s=%s:REPORTER:true" % (t, i))
    pool.append("%s:%s" % (t, i))
print("MP4_TEST_ACTORS='%s'" % ";".join(entries))
print("MP4_TEST_POOL='%s'" % ",".join(pool))
