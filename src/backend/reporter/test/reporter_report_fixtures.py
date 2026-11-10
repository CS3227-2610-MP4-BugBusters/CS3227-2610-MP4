"""Fixture helpers for tasks 2.4-2.11: seed or backdate report rows directly in the database (D10)."""
import re
import threading
import time
import uuid

from reporter_api_support import psql, request

PATH = "/api/reporter/reports"
REQUEST_ID = re.compile(rb'"requestId"\s*:\s*"[^"]*"')


def sql_text(s):
    """Hex-decoded literal so any text round-trips exactly without quoting concerns."""
    return "convert_from(decode('%s','hex'),'UTF8')" % s.encode("utf-8").hex()


def seed(reporter_id, title="Seeded", description="Seeded description", age_seconds=3600, report_id=None):
    """Inserts a report created age_seconds ago (older than the 60 s window by default)."""
    rid = report_id or str(uuid.uuid4())
    psql("INSERT INTO reports (id,title,description,reporter_id,status,version,created_at,updated_at) "
         "VALUES ('%s',%s,%s,'%s','SUBMITTED',0,now()-interval '%s seconds',now()-interval '%s seconds')"
         % (rid, sql_text(title), sql_text(description), reporter_id, age_seconds, age_seconds))
    return rid


def seed_at(reporter_id, report_id, created_iso, title="Seeded"):
    psql("INSERT INTO reports (id,title,description,reporter_id,status,version,created_at,updated_at) "
         "VALUES ('%s',%s,'d','%s','SUBMITTED',0,'%s','%s')"
         % (report_id, sql_text(title), reporter_id, created_iso, created_iso))


def backdate(report_id, age_seconds):
    psql("UPDATE reports SET created_at=now()-interval '%s seconds', updated_at=now()-interval '%s seconds' "
         "WHERE id='%s'" % (age_seconds, age_seconds, report_id))


def backdate_all(reporter_id, age_seconds):
    psql("UPDATE reports SET created_at=now()-interval '%s seconds', updated_at=now()-interval '%s seconds' "
         "WHERE reporter_id='%s'" % (age_seconds, age_seconds, reporter_id))


def create(token, title="T", description="D", **kw):
    return request("POST", PATH, body={"title": title, "description": description}, token=token, **kw)


def normalise_404(res):
    """(status, selected headers, body with the requestId value removed) for byte comparison."""
    st, headers, payload = res
    keep = {k: v for k, v in headers.items() if k in ("content-type", "cache-control")}
    body = REQUEST_ID.sub(b'"requestId":"<id>"', payload)
    return st, keep, body


# ---- additions for task 2.10 (edit); nothing above this line changed ----
def edit(token, report_id, title="New title", description="New description", version=0, **kw):
    return request("PUT", PATH + "/" + report_id,
                   body={"title": title, "description": description, "version": version}, token=token, **kw)


def snapshot(report_id):
    """Whole stored row as JSON text (empty string when absent), for 'nothing changed' comparisons."""
    return psql("SELECT row_to_json(r)::text FROM reports r WHERE id='%s'" % report_id)


def assign_sql(report_id, assignee_id):
    """The Maintainer side's assignment, as direct SQL (the Maintainer endpoint is out of scope here)."""
    psql("UPDATE reports SET assignee_id='%s', status='ASSIGNED' WHERE id='%s'" % (assignee_id, report_id))


def assign_holding_lock(report_id, assignee_id, hold_seconds=2):
    """Assigns in one database transaction that keeps its row lock for hold_seconds, then commits. Returns a
    thread once the UPDATE is running, so a request sent now must wait for the lock and then see the commit."""
    marker = "t210lock-" + report_id

    def run():
        psql("UPDATE reports SET assignee_id='%s', status='ASSIGNED' WHERE id='%s'; "
             "SELECT pg_sleep(%s) /* %s */" % (assignee_id, report_id, hold_seconds, marker))

    t = threading.Thread(target=run)
    t.start()
    for _ in range(100):
        if int(psql("SELECT count(*) FROM pg_stat_activity WHERE state='active' AND pid<>pg_backend_pid() "
                    "AND query LIKE '%%%s%%'" % marker)) > 0:
            break
        time.sleep(0.05)
    else:
        raise RuntimeError("assignment transaction did not start")
    time.sleep(0.3)  # let the UPDATE statement itself run before the sleep begins
    return t
