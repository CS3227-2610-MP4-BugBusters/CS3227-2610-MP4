#!/usr/bin/env python3
"""Exercise report schema migrations through the actual application startup."""

import os
import pathlib
import subprocess
import sys
import time
import urllib.error
import urllib.parse
import urllib.request
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[4]
AUTH = ROOT / "src/backend/auth"
URI = os.environ.get("MP4_TEST_DATABASE_URI")


def run(args, **kwargs):
    return subprocess.run(args, text=True, capture_output=True, **kwargs)


def psql(database, sql):
    result = run(["psql", "-X", "-v", "ON_ERROR_STOP=1", "-d", database, "-A", "-t", "-c", sql], check=True)
    return result.stdout.strip()


def database_uri(database):
    return URI.rsplit("/", 1)[0] + "/" + database


def start_app(database, port):
    parsed_uri = urllib.parse.urlsplit(URI)
    app_uri = f"jdbc:postgresql://{parsed_uri.hostname}:{parsed_uri.port}/{database}"
    args = [
        str(AUTH / "gradlew"), "--no-daemon", "runAuth",
        f"--args=--server.address=127.0.0.1 --server.port={port} "
        f"--mp4.auth.jdbc-url={app_uri} "
        f"--mp4.auth.db-user={os.environ.get('PGUSER', '')} "
        f"--mp4.auth.db-password={os.environ.get('PGPASSWORD', '')} "
        "--mp4.auth.rate-limit-secret=startup-migration-test-secret-0123456789abcdef --mp4.auth.local=true",
    ]
    process = subprocess.Popen(args, cwd=AUTH, stdout=subprocess.PIPE, stderr=subprocess.STDOUT, text=True)
    deadline = time.monotonic() + 90
    output = []
    while time.monotonic() < deadline and process.poll() is None:
        line = process.stdout.readline()
        if line:
            output.append(line)
        try:
            with urllib.request.urlopen(f"http://127.0.0.1:{port}/api/health", timeout=1):
                return process, output
        except (urllib.error.URLError, TimeoutError):
            pass
    if process.poll() is None:
        process.terminate()
        process.wait(timeout=10)
    output.extend(process.stdout.readlines())
    return process, output


def stop_app(process):
    if process.poll() is None:
        process.terminate()
        try:
            process.wait(timeout=15)
        except subprocess.TimeoutExpired:
            process.kill()
            process.wait(timeout=5)


def free_port():
    import socket
    with socket.socket() as server:
        server.bind(("127.0.0.1", 0))
        return server.getsockname()[1]


def main():
    if not URI:
        raise RuntimeError("runner setup error: MP4_TEST_DATABASE_URI is not set")
    if not os.environ.get("PGUSER") or not os.environ.get("PGPASSWORD"):
        raise RuntimeError("runner setup error: disposable PostgreSQL credentials are unavailable")

    suffix = uuid.uuid4().hex[:10]
    fresh = f"mp4_report_startup_{suffix}"
    auth_only = f"mp4_report_auth_only_{suffix}"
    incompatible = f"mp4_report_incompatible_{suffix}"
    psql("template1", f'CREATE DATABASE "{fresh}"')
    failures = []
    try:
        first, first_output = start_app(fresh, free_port())
        if first.poll() is not None:
            failures.append(f"fresh database application startup failed: {''.join(first_output)[-3000:]}")
        else:
            fresh_uri = database_uri(fresh)
            tables = psql(fresh_uri,
                          "SELECT to_regclass('public.reports'), to_regclass('public.investigation_checklists')")
            if tables != "reports|investigation_checklists":
                failures.append(f"fresh startup did not create reports and checklist tables (found {tables!r})")
            marker = "00000000-0000-0000-0000-000000000111"
            psql(fresh_uri, f"INSERT INTO reports(id,title,description,reporter_id) VALUES ('{marker}','startup marker','d','00000000-0000-0000-0000-000000000112')")
            stop_app(first)
            second, second_output = start_app(fresh, free_port())
            if second.poll() is not None:
                failures.append(f"restart failed: {''.join(second_output)[-3000:]}")
            else:
                retained = psql(fresh_uri, f"SELECT title FROM reports WHERE id='{marker}'")
                if retained != "startup marker":
                    failures.append(f"restart did not preserve preexisting report data (found {retained!r})")
            stop_app(second)

        # Clone a genuinely provisioned application database, then remove only shared state.
        psql("template1", f'CREATE DATABASE "{auth_only}" TEMPLATE "{fresh}"')
        psql("template1", f'CREATE DATABASE "{incompatible}" TEMPLATE "{fresh}"')
        for database in (auth_only, incompatible):
            psql(database_uri(database),
                 "DROP TABLE investigation_checklists, reports, mp4_shared_schema_history")

        auth_tables = psql(database_uri(auth_only),
                           "SELECT to_regclass('public.app_accounts'), to_regclass('public.mp4_auth_schema_history')")
        if auth_tables != "app_accounts|mp4_auth_schema_history":
            failures.append(f"auth-only fixture is not preprovisioned (found {auth_tables!r})")
        migrated, migrated_output = start_app(auth_only, free_port())
        if migrated.poll() is not None:
            failures.append(f"startup rejected the auth-only preprovisioned database: {''.join(migrated_output)[-3000:]}")
        else:
            tables = psql(database_uri(auth_only),
                          "SELECT to_regclass('public.reports'), to_regclass('public.investigation_checklists')")
            if tables != "reports|investigation_checklists":
                failures.append(f"auth-only startup did not install shared tables (found {tables!r})")
        stop_app(migrated)

        psql(database_uri(incompatible), "CREATE TABLE reports (id integer PRIMARY KEY)")
        auth_tables = psql(database_uri(incompatible),
                           "SELECT to_regclass('public.app_accounts'), to_regclass('public.mp4_auth_schema_history')")
        if auth_tables != "app_accounts|mp4_auth_schema_history":
            failures.append(f"incompatible-schema fixture lacks auth tables (found {auth_tables!r})")
        rejected, rejected_output = start_app(incompatible, free_port())
        if rejected.poll() is None:
            failures.append("startup accepted an incompatible pre-existing reports table")
            stop_app(rejected)
        remaining = psql(database_uri(incompatible),
                         "SELECT data_type FROM information_schema.columns WHERE table_schema='public' AND table_name='reports' AND column_name='id'")
        checklist = psql(database_uri(incompatible), "SELECT to_regclass('public.investigation_checklists')")
        if remaining != "integer" or checklist not in ("", "NULL"):
            failures.append("failed startup changed or extended the incompatible shared schema")
    finally:
        psql("template1", f'DROP DATABASE "{fresh}" WITH (FORCE)')
        psql("template1", f'DROP DATABASE "{auth_only}" WITH (FORCE)')
        psql("template1", f'DROP DATABASE "{incompatible}" WITH (FORCE)')

    if failures:
        for failure in failures:
            print(f"FAIL: {failure}", file=sys.stderr)
        return 1
    print("PASS: startup installs shared schemas for fresh and auth-only databases, preserves them on restart, and safely rejects incompatible shared state")
    return 0


if __name__ == "__main__":
    try:
        raise SystemExit(main())
    except subprocess.CalledProcessError as error:
        print(error.stderr or error.stdout, file=sys.stderr)
        raise SystemExit(error.returncode)
