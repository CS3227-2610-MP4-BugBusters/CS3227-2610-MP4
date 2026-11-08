#!/usr/bin/env python3
"""Disposable PostgreSQL checks for shared report migration SR-01 through SR-03."""

import os
import pathlib
import subprocess
import sys
import tempfile
import time


ROOT = pathlib.Path(__file__).resolve().parents[4]
MIGRATION = ROOT / "src/backend/shared/migrations/001_create_reports.sql"
CHECKLIST_MIGRATION = ROOT / "src/backend/developer/migrations/create_investigation_checklists.sql"
DB = os.environ.get("MP4_TEST_DATABASE_NAME")


def psql(sql=None, *args, check=True, **kwargs):
    command = ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", DB, *args]
    return subprocess.run(command, input=sql, text=True, capture_output=True, check=check, **kwargs)


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def apply(path):
    return psql(None, "-f", str(path))


def main():
    require(DB, "runner setup error: MP4_TEST_DATABASE_NAME is not set")
    # A setup control: fail distinctly if the disposable PostgreSQL instance is unavailable.
    control = psql("SELECT current_database();")
    require(control.stdout.strip().splitlines()[-2].strip() == DB,
            "runner setup error: connected to an unexpected database")
    relation = psql("SELECT to_regclass('public.reports');").stdout.strip().splitlines()[-2].strip()
    require(relation in ("", "NULL"),
            "runner setup error: reports table already exists in fresh database")

    if not MIGRATION.is_file():
        print(f"RED (expected): target migration is missing: {MIGRATION}", file=sys.stderr)
        return 1

    apply(MIGRATION)
    apply(CHECKLIST_MIGRATION)

    psql("""
INSERT INTO reports (id, title, description, reporter_id)
VALUES ('00000000-0000-0000-0000-000000000001', 'Title', 'Description',
        '00000000-0000-0000-0000-000000000002');
DO $$
BEGIN
  IF (SELECT assignee_id IS NOT NULL OR status <> 'SUBMITTED' OR version <> 0
             OR current_proposal_id IS NOT NULL FROM reports
       WHERE id = '00000000-0000-0000-0000-000000000001') THEN
    RAISE EXCEPTION 'new report defaults are incorrect';
  END IF;
END $$;
""")

    for status in ("SUBMITTED", "ASSIGNED", "IN_PROGRESS", "RESOLUTION_PROPOSED", "CLOSED"):
        psql("INSERT INTO reports (id,title,description,reporter_id,status) VALUES (%s,'t','d',%s,%s);" % (
            "'00000000-0000-0000-0000-%012d'" % (10 + len(status)),
            "'00000000-0000-0000-0000-000003000001'", "'%s'" % status))

    # PostgreSQL must reject unsupported states, negative versions, and omitted required values.
    psql("""
DO $$
DECLARE rejected boolean;
BEGIN
  rejected := false;
  BEGIN
    INSERT INTO reports (id,title,description,reporter_id,status)
    VALUES ('10000000-0000-0000-0000-000000000001','t','d','00000000-0000-0000-0000-000003000001','NOPE');
  EXCEPTION WHEN check_violation THEN rejected := true; END;
  IF NOT rejected THEN RAISE EXCEPTION 'unsupported status was accepted'; END IF;
  rejected := false;
  BEGIN
    UPDATE reports SET status='NOPE'
    WHERE id='00000000-0000-0000-0000-000000000001';
  EXCEPTION WHEN check_violation THEN rejected := true; END;
  IF NOT rejected THEN RAISE EXCEPTION 'unsupported status update was accepted'; END IF;
  rejected := false;
  BEGIN
    INSERT INTO reports (id,title,description,reporter_id,version)
    VALUES ('10000000-0000-0000-0000-000000000002','t','d','00000000-0000-0000-0000-000003000001',-1);
  EXCEPTION WHEN check_violation THEN rejected := true; END;
  IF NOT rejected THEN RAISE EXCEPTION 'negative version was accepted'; END IF;
  rejected := false;
  BEGIN
    UPDATE reports SET version=-1
    WHERE id='00000000-0000-0000-0000-000000000001';
  EXCEPTION WHEN check_violation THEN rejected := true; END;
  IF NOT rejected THEN RAISE EXCEPTION 'negative version update was accepted'; END IF;
  rejected := false;
  BEGIN
    INSERT INTO reports (id,description,reporter_id) VALUES ('10000000-0000-0000-0000-000000000003','d','00000000-0000-0000-0000-000003000001');
  EXCEPTION WHEN not_null_violation THEN rejected := true; END;
  IF NOT rejected THEN RAISE EXCEPTION 'missing title was accepted'; END IF;
  rejected := false;
  BEGIN
    UPDATE reports SET title=NULL
    WHERE id='00000000-0000-0000-0000-000000000001';
  EXCEPTION WHEN not_null_violation THEN rejected := true; END;
  IF NOT rejected THEN RAISE EXCEPTION 'null required title update was accepted'; END IF;
END $$;
""")

    psql("""
INSERT INTO investigation_checklists(report_id, draft_steps)
VALUES ('00000000-0000-0000-0000-000000000001', ARRAY['reproduce']);
DO $$ BEGIN
  IF NOT EXISTS (SELECT 1 FROM reports r JOIN investigation_checklists c ON c.report_id=r.id
                 WHERE r.id='00000000-0000-0000-0000-000000000001'
                   AND c.draft_steps=ARRAY['reproduce']) THEN
    RAISE EXCEPTION 'checklist did not persist against report';
  END IF;
END $$;
""")

    # Two psql connections target the same expected version. The first holds its row lock
    # after updating; the second waits, rechecks version=0, and must update zero rows.
    psql("INSERT INTO reports (id,title,description,reporter_id) VALUES ('20000000-0000-0000-0000-000000000001','before','d','00000000-0000-0000-0000-000003000001');")
    with tempfile.TemporaryDirectory(prefix="mp4-report-cas-") as tmp:
        first_out = pathlib.Path(tmp) / "first.out"
        first_err = pathlib.Path(tmp) / "first.err"
        ready = pathlib.Path(tmp) / "first-update-held-lock"
        release = pathlib.Path(tmp) / "release-first-writer"
        second_out = pathlib.Path(tmp) / "second.out"
        second_err = pathlib.Path(tmp) / "second.err"
        first_stdout = first_out.open("w")
        first_stderr = first_err.open("w")
        first = subprocess.Popen(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", DB],
                                 stdin=subprocess.PIPE, stdout=first_stdout, stderr=first_stderr, text=True)
        second = None
        second_stdout = second_stderr = None
        first_sql = ("BEGIN; UPDATE reports SET title='writer one', version=version+1 "
                     "WHERE id='20000000-0000-0000-0000-000000000001' AND version=0;\n"
                     f"\\! touch '{ready}'\n\\! while [ ! -f '{release}' ]; do sleep 0.05; done\nCOMMIT;")
        try:
            first.stdin.write(first_sql)
            first.stdin.close()
            for _ in range(100):
                if ready.exists() or first.poll() is not None:
                    break
                time.sleep(0.05)
            require(ready.exists(), "runner/test error: first writer did not acquire update lock")

            second_env = os.environ.copy()
            second_env["PGAPPNAME"] = "mp4_shared_report_writer_two"
            second_stdout = second_out.open("w")
            second_stderr = second_err.open("w")
            second = subprocess.Popen(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", DB],
                                      stdin=subprocess.PIPE, stdout=second_stdout, stderr=second_stderr,
                                      text=True, env=second_env)
            second.stdin.write("SET lock_timeout='8s'; UPDATE reports SET title='writer two', version=version+1 "
                               "WHERE id='20000000-0000-0000-0000-000000000001' AND version=0;\n")
            second.stdin.close()

            observed_wait = False
            for _ in range(100):
                waiting = psql("SELECT count(*) FROM pg_stat_activity "
                               "WHERE application_name='mp4_shared_report_writer_two' "
                               "AND state='active' AND wait_event_type='Lock';", "-A", "-t")
                if waiting.stdout.strip() == "1":
                    observed_wait = True
                    break
                if second.poll() is not None:
                    break
                time.sleep(0.05)
            require(observed_wait, "second writer was not observed waiting on the first writer's row lock")
        finally:
            # Always unblock writer one, including assertion/timeout failures.
            release.touch()
            try:
                first.wait(timeout=10)
            except subprocess.TimeoutExpired:
                first.kill()
                first.wait(timeout=5)
            if second is not None:
                try:
                    second.wait(timeout=10)
                except subprocess.TimeoutExpired:
                    second.kill()
                    second.wait(timeout=5)
            first_stdout.close()
            first_stderr.close()
            if second_stdout:
                second_stdout.close()
            if second_stderr:
                second_stderr.close()
        require(first.returncode == 0, f"runner/test error in first competing writer: {first_err.read_text()}")
        require(second is not None and second.returncode == 0,
                f"runner/test error in second competing writer: {second_err.read_text() if second else ''}")
        require("UPDATE 0" in second_out.read_text(), "stale competing writer did not update zero rows")
    final = psql("SELECT title || '|' || version FROM reports WHERE id='20000000-0000-0000-0000-000000000001';")
    require("writer one|1" in final.stdout, "committed writer's value/version did not remain")
    print("PASS: SR-01 constraints/defaults; SR-02 competing version-checked updates; SR-03 checklist migration integration")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(error.stderr or error.stdout, file=sys.stderr)
        raise SystemExit(error.returncode)
