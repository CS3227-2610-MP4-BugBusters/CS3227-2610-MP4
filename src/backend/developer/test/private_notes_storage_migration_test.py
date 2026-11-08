#!/usr/bin/env python3
"""Disposable PostgreSQL checks for the Developer private-notes table migration."""

import os
import pathlib
import subprocess
import sys


ROOT = pathlib.Path(__file__).resolve().parents[4]
REPORTS_MIGRATION = ROOT / "src/backend/shared/migrations/001_create_reports.sql"
NOTES_MIGRATION = ROOT / "src/backend/developer/migrations/create_developer_private_notes.sql"
DB = os.environ.get("MP4_TEST_DATABASE_NAME")


def psql(sql=None, *args):
    return subprocess.run(
        ["psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", DB, *args],
        input=sql,
        text=True,
        capture_output=True,
        check=True,
    )


def require(condition, message):
    if not condition:
        raise AssertionError(message)


def apply(path):
    psql(None, "-f", str(path))


def main():
    require(DB, "runner setup error: MP4_TEST_DATABASE_NAME is not set")
    control = psql("SELECT current_database();")
    require(control.stdout.strip().splitlines()[-2].strip() == DB,
            "runner setup error: connected to an unexpected database")
    relation = psql("SELECT to_regclass('public.reports');").stdout.strip().splitlines()[-2].strip()
    require(relation in ("", "NULL"),
            "runner setup error: reports table already exists in fresh database")

    apply(REPORTS_MIGRATION)
    if not NOTES_MIGRATION.is_file():
        print(f"RED (missing migration artifact): {NOTES_MIGRATION}", file=sys.stderr)
        return 1
    apply(NOTES_MIGRATION)

    psql("""
CREATE TABLE resolution_proposals (
  id uuid PRIMARY KEY,
  report_id uuid NOT NULL REFERENCES reports(id),
  status text NOT NULL,
  version bigint NOT NULL,
  contents text NOT NULL
);
INSERT INTO reports (id, title, description, reporter_id, status, version)
VALUES
 ('00000000-0000-0000-0000-000000000001','one','d','00000000-0000-0000-0000-000000000099','RESOLUTION_PROPOSED',7),
 ('00000000-0000-0000-0000-000000000002','two','d','00000000-0000-0000-0000-000000000099','ASSIGNED',3);
INSERT INTO resolution_proposals (id, report_id, status, version, contents)
VALUES ('00000000-0000-0000-0000-000000000010',
        '00000000-0000-0000-0000-000000000001', 'PENDING', 2, 'Pending proposal');
UPDATE reports SET current_proposal_id='00000000-0000-0000-0000-000000000010'
WHERE id='00000000-0000-0000-0000-000000000001';
DO $$
DECLARE note_row developer_private_notes%ROWTYPE;
BEGIN
  INSERT INTO developer_private_notes (report_id)
  VALUES ('00000000-0000-0000-0000-000000000001') RETURNING * INTO note_row;
  IF note_row.body <> '' OR note_row.notes_revision <> 0 THEN
    RAISE EXCEPTION 'empty note defaults are incorrect';
  END IF;

  UPDATE developer_private_notes SET body='local note', notes_revision=1
  WHERE report_id='00000000-0000-0000-0000-000000000001';
  UPDATE developer_private_notes SET body='', notes_revision=notes_revision+1
  WHERE report_id='00000000-0000-0000-0000-000000000001';
  IF NOT EXISTS (SELECT 1 FROM developer_private_notes
                 WHERE report_id='00000000-0000-0000-0000-000000000001'
                   AND body='' AND notes_revision=2) THEN
    RAISE EXCEPTION 'clearing did not retain and advance the revision';
  END IF;

  BEGIN
    INSERT INTO developer_private_notes (report_id)
    VALUES ('00000000-0000-0000-0000-000000000001');
    RAISE EXCEPTION 'duplicate report note was accepted';
  EXCEPTION WHEN unique_violation THEN NULL; END;
  BEGIN
    INSERT INTO developer_private_notes (report_id)
    VALUES ('00000000-0000-0000-0000-000000000003');
    RAISE EXCEPTION 'orphan note was accepted';
  EXCEPTION WHEN foreign_key_violation THEN NULL; END;
  BEGIN
    UPDATE developer_private_notes SET notes_revision=-1
    WHERE report_id='00000000-0000-0000-0000-000000000001';
    RAISE EXCEPTION 'negative notes revision was accepted';
  EXCEPTION WHEN check_violation THEN NULL; END;

  -- Storage-only SQL must leave the aggregate report and pending proposal link as-is.
  IF NOT EXISTS (SELECT 1 FROM reports
                 WHERE id='00000000-0000-0000-0000-000000000001'
                   AND version=7 AND status='RESOLUTION_PROPOSED'
                   AND current_proposal_id='00000000-0000-0000-0000-000000000010') THEN
    RAISE EXCEPTION 'note-only SQL changed report state';
  END IF;
  IF NOT EXISTS (SELECT 1 FROM resolution_proposals
                 WHERE id='00000000-0000-0000-0000-000000000010'
                   AND status='PENDING' AND version=2
                   AND contents='Pending proposal') THEN
    RAISE EXCEPTION 'note-only SQL changed the pending proposal';
  END IF;
END $$;
""")
    print("PASS: private-notes storage defaults, uniqueness, report FK, revision check, clear retention, and note-only report-state isolation")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(error.stderr or error.stdout, file=sys.stderr)
        raise SystemExit(error.returncode)
