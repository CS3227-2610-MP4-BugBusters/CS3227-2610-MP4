#!/usr/bin/env python3
"""Create an isolated PostgreSQL fixture, apply existing migrations, run auth setup control."""
import os
import pathlib
import shutil
import socket
import subprocess
import sys
import tempfile
import secrets
import shlex
import argparse

ROOT = pathlib.Path(__file__).resolve().parents[4]
AUTH = ROOT / "src/backend/auth"


def executable(name):
    configured = os.environ.get("MP4_POSTGRES_BIN")
    candidates = ([pathlib.Path(configured) / (name + (".exe" if os.name == "nt" else ""))] if configured else [])
    found = shutil.which(name)
    if found:
        candidates.append(pathlib.Path(found))
    if os.name == "nt":
        candidates.append(pathlib.Path(r"C:\Program Files\PostgreSQL\17\bin") / (name + ".exe"))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"Required PostgreSQL tool not found: {name}; set MP4_POSTGRES_BIN or PATH")


def run(args, **kwargs):
    subprocess.run(args, check=True, **kwargs)


def main(test_patterns=()):
    initdb, pg_ctl, psql = (executable(x) for x in ("initdb", "pg_ctl", "psql"))
    wrapper = AUTH / ("gradlew.bat" if os.name == "nt" else "gradlew")
    if not wrapper.is_file():
        raise RuntimeError("Gradle wrapper is missing from src/backend/auth")
    temp_root = pathlib.Path(tempfile.gettempdir()).resolve()
    scratch = pathlib.Path(tempfile.mkdtemp(prefix="mp4-auth-setup-")).resolve()
    if not scratch.is_relative_to(temp_root):
        raise RuntimeError("Resolved scratch directory escaped system temporary directory")
    data = scratch / "data"
    pwfile = scratch / "init-password"
    password = secrets.token_urlsafe(32)
    port = None
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        port = sock.getsockname()[1]
    started = False
    try:
        pwfile.write_text(password, encoding="utf-8")
        if os.name != "nt":
            pwfile.chmod(0o600)
        run([initdb, "-D", str(data), "-U", "postgres", "--pwfile", str(pwfile), "--auth-local=scram-sha-256", "--auth-host=scram-sha-256", "--encoding=UTF8", "--no-locale"], stdout=subprocess.DEVNULL)
        pwfile.unlink(missing_ok=True)
        options = f"-h 127.0.0.1 -p {port}"
        if os.name != "nt":
            options += f" -k {shlex.quote(str(scratch))}"
        run([pg_ctl, "-D", str(data), "-l", str(scratch / "postgres.log"), "-o", options, "-w", "start"], stdout=subprocess.DEVNULL)
        started = True
        env = os.environ.copy()
        for key in tuple(env):
            if key.upper().startswith("PG"):
                del env[key]
        env["PGPASSWORD"] = password
        env["PGHOST"], env["PGPORT"], env["PGUSER"] = "127.0.0.1", str(port), "postgres"
        run([psql, "-v", "ON_ERROR_STOP=1", "-d", "postgres", "-c", "CREATE DATABASE mp4_auth_setup"], env=env, stdout=subprocess.DEVNULL)
        env.update({"MP4_AUTH_TEST_JDBC_URL": f"jdbc:postgresql://127.0.0.1:{port}/mp4_auth_setup", "MP4_AUTH_TEST_DB_USER": "postgres", "MP4_AUTH_TEST_DB_PASSWORD": password})
        run([str(wrapper), "--no-daemon", "test", "--tests", "mp4.auth.RunnerSetupTest"], cwd=AUTH, env=env)
        tasks = ["test"] if test_patterns else ["check"]
        for pattern in test_patterns:
            tasks.extend(["--tests", pattern])
        run([str(wrapper), "--no-daemon", *tasks], cwd=AUTH, env=env)
    finally:
        if data.exists() and (started or (data / "postmaster.pid").exists()):
            run([pg_ctl, "-D", str(data), "-m", "immediate", "-w", "stop"], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        if data.exists() and (data / "postmaster.pid").exists():
            raise RuntimeError("Refusing cleanup because the owned PostgreSQL instance is still running")
        resolved = scratch.resolve()
        if not resolved.is_relative_to(temp_root) or resolved == temp_root:
            raise RuntimeError("Refusing cleanup: resolved scratch path is not safely under system temporary directory")
        shutil.rmtree(resolved, ignore_errors=False)


if __name__ == "__main__":
    try:
        parser = argparse.ArgumentParser(description=__doc__)
        parser.add_argument("--tests", action="append", default=[], metavar="CLASS_OR_METHOD",
                            help="Run a focused Gradle test selection in the same disposable fixture; repeatable")
        main(parser.parse_args().tests)
    except Exception as exc:
        print(f"Setup runner failed: {type(exc).__name__}: {exc}", file=sys.stderr)
        raise
