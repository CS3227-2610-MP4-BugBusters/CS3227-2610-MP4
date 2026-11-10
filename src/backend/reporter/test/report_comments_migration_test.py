#!/usr/bin/env python3
"""Red test for report-discussion D1 / task 2.1 (003_report_comments.sql).

Run via: sh src/backend/reporter/test/run-reporter-db.sh python -B <this file>
"""

import os
import subprocess
import sys

CONTAINER = os.environ.get("MP4_TEST_PG_CONTAINER")
DB = os.environ.get("MP4_TEST_DATABASE_NAME")
BASE = os.path.abspath(os.path.join(os.path.dirname(__file__), "..", "..", "..", ".."))
MIGRATIONS = os.path.join(BASE, "src", "backend", "shared", "migrations")
BACKEND = os.path.join(BASE, "src", "backend")
DEPLOYED = [  # Flyway order of the deployed app
    os.path.join(MIGRATIONS, "001_create_reports.sql"),
    os.path.join(BACKEND, "developer", "migrations", "create_investigation_checklists.sql"),
    os.path.join(BACKEND, "maintainer", "src", "main", "resources", "db", "shared", "V3__maintainer_evidence.sql"),
    os.path.join(BACKEND, "auth", "src", "main", "resources", "db", "shared", "V4__reporter_report_timestamps.sql"),
]
V5 = os.path.join(BACKEND, "auth", "src", "main", "resources", "db", "shared", "V5__report_comments.sql")

RID = "40000000-0000-0000-0000-000000000001"
AUTHOR = "40000000-0000-0000-0000-000000000002"
CID = "40000000-0000-0000-0000-000000000003"


def sql(text, db=None):
    result = subprocess.run(
        ["docker", "exec", "-i", CONTAINER, "psql", "-v", "ON_ERROR_STOP=1", "-At",
         "-U", "mp4_fixture", "-d", db or DB],
        input=text, text=True, capture_output=True)
    if result.returncode != 0:
        raise RuntimeError((result.stderr or result.stdout).strip())
    return [line for line in result.stdout.splitlines() if line.strip()]


def rejected(text, fragment):
    """Run SQL expecting failure whose message contains fragment."""
    try:
        sql(text)
    except RuntimeError as error:
        assert fragment in str(error).lower(), f"wrong error, wanted {fragment!r}: {error}"
        return
    raise AssertionError("statement was accepted but must be rejected")


def seed_report():
    sql(f"INSERT INTO reports (id, title, description, reporter_id) VALUES "
        f"('{RID}','t','d','{AUTHOR}') ON CONFLICT DO NOTHING;")


def insert(cols, vals):
    return f"INSERT INTO report_comments ({cols}) VALUES ({vals});"


def check_table_columns():
    rows = sql("SELECT column_name, data_type, is_nullable, coalesce(column_default,'') "
               "FROM information_schema.columns WHERE table_schema='public' "
               "AND table_name='report_comments';")
    cols = {r.split("|")[0]: r.split("|")[1:] for r in rows}
    want = {"id": ("uuid", "NO"), "report_id": ("uuid", "NO"), "author_id": ("uuid", "NO"),
            "author_role": ("text", "NO"), "text": ("text", "NO"),
            "created_at": ("timestamp with time zone", "NO")}
    assert set(cols) == set(want), f"column set {sorted(cols)} != {sorted(want)}"
    for name, (dtype, nullable) in want.items():
        assert cols[name][0] == dtype and cols[name][1] == nullable, f"{name}: {cols[name]}"
    assert cols["created_at"][2].lower().startswith(("now()", "current_timestamp")), cols["created_at"][2]


def check_primary_key():
    rows = sql("SELECT a.attname FROM pg_index i JOIN pg_attribute a ON a.attrelid=i.indrelid "
               "AND a.attnum = ANY(i.indkey) WHERE i.indrelid = to_regclass('public.report_comments') "
               "AND i.indisprimary;")
    assert rows == ["id"], f"primary key columns {rows}"


def check_fk_rejects_unknown_report():
    rejected(insert("id, report_id, author_id, author_role, text",
                    f"'{CID}','40000000-0000-0000-0000-0000000000ff','{AUTHOR}','REPORTER','x'"),
             "foreign key")


def check_role_check():
    seed_report()
    for role in ("ADMIN", "reporter", ""):
        rejected(insert("id, report_id, author_id, author_role, text",
                        f"'{CID}','{RID}','{AUTHOR}','{role}','x'"), "check constraint")
    for i, role in enumerate(("REPORTER", "DEVELOPER", "MAINTAINER")):
        sql(insert("id, report_id, author_id, author_role, text",
                   f"'40000000-0000-0000-0000-00000000010{i}','{RID}','{AUTHOR}','{role}','x'"))


def check_not_nulls():
    seed_report()
    base = {"id": f"'{CID}'", "report_id": f"'{RID}'", "author_id": f"'{AUTHOR}'",
            "author_role": "'REPORTER'", "text": "'x'"}
    for col in base:
        vals = dict(base, **{col: "NULL"})
        rejected(insert(", ".join(vals), ", ".join(vals.values())), "null")


def check_created_at_default():
    seed_report()
    rows = sql(insert("id, report_id, author_id, author_role, text",
                      f"'40000000-0000-0000-0000-000000000200','{RID}','{AUTHOR}','REPORTER','x'")
               + " SELECT created_at IS NOT NULL FROM report_comments "
                 "WHERE id='40000000-0000-0000-0000-000000000200';")
    assert rows[-1] == "t", rows


def index_columns():
    return sql("SELECT array_to_string(array_agg(a.attname ORDER BY k.ord), ',') "
               "FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY k(attnum, ord) "
               "JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum "
               "WHERE i.indrelid = to_regclass('public.report_comments') GROUP BY i.indexrelid;")


def check_indexes():
    got = index_columns()
    for want in ("report_id,created_at,id", "author_id,created_at"):
        assert want in got, f"no index with key columns ({want}); found {got}"


def check_apply_order_fresh_db():
    files = sorted(f for f in os.listdir(MIGRATIONS) if f.endswith(".sql"))
    assert "003_report_comments.sql" in files, f"migration file missing; found {files}"
    assert files.index("003_report_comments.sql") > files.index("002_add_report_timestamps.sql"), files
    fresh = "mp4_comments_order_test"
    sql(f"DROP DATABASE IF EXISTS {fresh};", "postgres")
    sql(f"CREATE DATABASE {fresh};", "postgres")
    try:
        for prefix in ("001", "002", "003"):
            name = next(x for x in files if x.startswith(prefix))
            with open(os.path.join(MIGRATIONS, name), encoding="utf-8") as fh:
                sql(fh.read(), fresh)
        assert sql("SELECT to_regclass('public.report_comments');", fresh) == ["report_comments"]
    finally:
        sql(f"DROP DATABASE IF EXISTS {fresh};", "postgres")


def read(path):
    with open(path, encoding="utf-8") as fh:
        return fh.read()


def shape(db):
    cols = sql("SELECT column_name, data_type, is_nullable, coalesce(column_default,'') "
               "FROM information_schema.columns WHERE table_schema='public' "
               "AND table_name='report_comments' ORDER BY column_name;", db)
    idx = sql("SELECT array_to_string(array_agg(a.attname ORDER BY k.ord), ',') "
              "FROM pg_index i CROSS JOIN LATERAL unnest(i.indkey::int2[]) WITH ORDINALITY k(attnum, ord) "
              "JOIN pg_attribute a ON a.attrelid=i.indrelid AND a.attnum=k.attnum "
              "WHERE i.indrelid = to_regclass('public.report_comments') "
              "AND NOT i.indisprimary GROUP BY i.indexrelid;", db)
    return cols, sorted(idx)


class scratch_db:
    def __init__(self, name):
        self.name = name

    def __enter__(self):
        sql(f"DROP DATABASE IF EXISTS {self.name};", "postgres")
        sql(f"CREATE DATABASE {self.name};", "postgres")
        return self.name

    def __exit__(self, *exc):
        sql(f"DROP DATABASE IF EXISTS {self.name};", "postgres")


def deployed_db_through_v4(db):
    for path in DEPLOYED:
        sql(read(path), db)
    sql(f"INSERT INTO reports (id, title, description, reporter_id) VALUES "
        f"('{RID}','t','d','{AUTHOR}');", db)
    sql(f"INSERT INTO report_comments (id, report_id, author_id, text) VALUES "
        f"('{CID}','{RID}','{AUTHOR}','legacy text');", db)


def check_deployed_v5_upgrades_v3_table():
    with scratch_db("mp4_comments_deployed_test") as db:
        deployed_db_through_v4(db)
        sql(read(V5), db)
        got_cols, got_idx = shape(db)
        want_cols = {"id": ("uuid", "NO"), "report_id": ("uuid", "NO"), "author_id": ("uuid", "NO"),
                     "author_role": ("text", "NO"), "text": ("text", "NO"),
                     "created_at": ("timestamp with time zone", "NO")}
        have = {r.split("|")[0]: tuple(r.split("|")[1:3]) for r in got_cols}
        assert have == want_cols, f"deployed column shape {have} != {want_cols}"
        default = {r.split("|")[0]: r.split("|")[3] for r in got_cols}["created_at"]
        assert default.lower().startswith(("now()", "current_timestamp")), default
        for want in ("report_id,created_at,id", "author_id,created_at"):
            assert want in got_idx, f"no index ({want}) after V5; found {got_idx}"
        for role in ("ADMIN", "reporter", ""):
            try:
                sql(f"INSERT INTO report_comments (id, report_id, author_id, author_role, text) VALUES "
                    f"('40000000-0000-0000-0000-000000000300','{RID}','{AUTHOR}','{role}','x');", db)
            except RuntimeError as error:
                assert "check constraint" in str(error).lower(), error
            else:
                raise AssertionError(f"role {role!r} accepted after V5")
        row = sql(f"SELECT author_role, text FROM report_comments WHERE id='{CID}';", db)
        assert row == ["MAINTAINER|legacy text"], f"pre-existing comment: {row}"


def check_deployed_v5_idempotent():
    with scratch_db("mp4_comments_deployed_test") as db:
        deployed_db_through_v4(db)
        sql(read(V5), db)
        before = (shape(db), sql("SELECT id, author_role, text, created_at FROM report_comments ORDER BY id;", db))
        sql(read(V5), db)
        after = (shape(db), sql("SELECT id, author_role, text, created_at FROM report_comments ORDER BY id;", db))
        assert before == after, f"second V5 run changed state: {before} -> {after}"


def check_fresh_and_deployed_shapes_identical():
    with scratch_db("mp4_comments_fresh_cmp") as fresh, scratch_db("mp4_comments_deployed_cmp") as dep:
        for name in ("001_create_reports.sql", "002_add_report_timestamps.sql", "003_report_comments.sql"):
            sql(read(os.path.join(MIGRATIONS, name)), fresh)
        deployed_db_through_v4(dep)
        sql(read(V5), dep)
        assert shape(fresh) == shape(dep), f"fresh {shape(fresh)} != deployed {shape(dep)}"


CHECKS = [
    ("D1 columns/types/nullability/default of report_comments", check_table_columns),
    ("D1 id is the primary key", check_primary_key),
    ("D1 FK rejects unknown report_id", check_fk_rejects_unknown_report),
    ("D1 author_role CHECK rejects other roles, accepts the three", check_role_check),
    ("D1 NOT NULL enforced on id/report_id/author_id/author_role/text", check_not_nulls),
    ("D1 created_at defaults on insert", check_created_at_default),
    ("D1 indexes (report_id, created_at, id) and (author_id, created_at)", check_indexes),
    ("Migration Plan: 003 applies after 001 and 002 on a fresh database", check_apply_order_fresh_db),
    ("D1 deployed: V5 alters the Maintainer V3 table to the D1 shape, backfills MAINTAINER", check_deployed_v5_upgrades_v3_table),
    ("D1 deployed: V5 is idempotent", check_deployed_v5_idempotent),
    ("D1 fresh (001-003) and deployed (V1-V5) report_comments shapes are identical", check_fresh_and_deployed_shapes_identical),
]


def main():
    if not CONTAINER or not DB:
        print("SETUP ERROR: run through run-reporter-db.sh", file=sys.stderr)
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
