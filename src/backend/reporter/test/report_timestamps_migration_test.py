#!/usr/bin/env python3
"""Red test for D7 Reporter timestamp migration (002_add_report_timestamps.sql).

Run via: sh src/backend/reporter/test/run-reporter-db.sh python -B <this file>
"""

import os
import subprocess
import sys

CONTAINER = os.environ.get("MP4_TEST_PG_CONTAINER")
DB = os.environ.get("MP4_TEST_DATABASE_NAME")

BASE_COLUMNS = {"id", "title", "description", "reporter_id", "assignee_id",
                "status", "version", "current_proposal_id"}
EXPECTED_COLUMNS = BASE_COLUMNS | {"created_at", "updated_at"}


def sql(text):
    """Run SQL through psql in the container; return stdout lines. Raises on error."""
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-v", "ON_ERROR_STOP=1", "-At",
         "-U", "mp4_fixture", "-d", DB],
        input=text, text=True, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return [line for line in result.stdout.splitlines() if line.strip()]


def columns():
    rows = sql("SELECT column_name, data_type, is_nullable, coalesce(column_default,'') "
               "FROM information_schema.columns WHERE table_schema='public' "
               "AND table_name='reports';")
    return {r.split("|")[0]: r.split("|")[1:] for r in rows}


def check_control():
    cols = columns()
    assert set(cols) >= BASE_COLUMNS, f"reports missing 001 columns: {BASE_COLUMNS - set(cols)}"
    assert sql("SELECT to_regclass('public.investigation_checklists');") == ["investigation_checklists"], \
        "checklist table missing"


def check_timestamp_columns():
    cols = columns()
    for name in ("created_at", "updated_at"):
        assert name in cols, f"column {name} is missing from reports"
        dtype, nullable, default = cols[name]
        assert dtype == "timestamp with time zone", f"{name} type is {dtype}, expected timestamptz"
        assert nullable == "NO", f"{name} must be NOT NULL"
        assert default.strip().lower().startswith(("now()", "current_timestamp")), \
            f"{name} default is {default!r}, expected now()"


def check_index():
    rows = sql("SELECT indexdef FROM pg_indexes WHERE schemaname='public' AND tablename='reports';")
    want = "(reporter_id, created_at DESC, id)"
    assert any(want in r for r in rows), f"no index on {want}; found: {rows}"


def check_no_extra_columns():
    got = set(columns())
    assert got == EXPECTED_COLUMNS, \
        f"column set mismatch: missing {sorted(EXPECTED_COLUMNS - got)}, extra {sorted(got - EXPECTED_COLUMNS)}"


def check_insert_defaults():
    rid = "30000000-0000-0000-0000-000000000001"
    rows = sql(f"INSERT INTO reports (id, title, description, reporter_id) VALUES "
               f"('{rid}','t','d','30000000-0000-0000-0000-000000000002'); "
               f"SELECT created_at IS NOT NULL AND updated_at IS NOT NULL AND created_at = updated_at "
               f"FROM reports WHERE id='{rid}';")
    assert rows and rows[-1] == "t", f"timestamps not set and equal on minimal insert: {rows}"


def check_checklist_insert():
    rid = "30000000-0000-0000-0000-000000000001"
    sql(f"INSERT INTO reports (id, title, description, reporter_id) VALUES "
        f"('{rid}','t','d','30000000-0000-0000-0000-000000000002') ON CONFLICT DO NOTHING; "
        f"INSERT INTO investigation_checklists(report_id, draft_steps) VALUES ('{rid}', ARRAY['reproduce']);")


CHECKS = [
    ("control: reports has 001 columns and checklist table exists", check_control),
    ("created_at/updated_at are timestamptz NOT NULL DEFAULT now()", check_timestamp_columns),
    ("index on (reporter_id, created_at DESC, id)", check_index),
    ("no columns beyond 001's 8 plus the 2 timestamps", check_no_extra_columns),
    ("minimal insert yields equal non-null timestamps", check_insert_defaults),
    ("checklist row insertable for stored report", check_checklist_insert),
]


def main():
    if not CONTAINER or not DB:
        print("SETUP ERROR: run through run-reporter-db.sh (MP4_TEST_PG_CONTAINER/DATABASE_NAME unset)",
              file=sys.stderr)
        return 2
    failed = 0
    for name, fn in CHECKS:
        try:
            fn()
            print(f"PASS: {name}")
        except Exception as error:  # noqa: BLE001
            failed += 1
            print(f"FAIL: {name} -- {error}")
    return 1 if failed else 0


if __name__ == "__main__":
    raise SystemExit(main())
