#!/usr/bin/env python3
"""Run the packaged frontend/API smoke check against a disposable local stack."""

import os
import pathlib
import secrets
import shutil
import socket
import subprocess
import sys
import tempfile
import time
import urllib.error
import urllib.request


ROOT = pathlib.Path(__file__).resolve().parents[5]
DIST = ROOT / "src/backend/auth/build/auth-distribution"
CLASSES = DIST / "classes"
LIB = DIST / "lib"
STATIC = CLASSES / "static"
TEMP_ROOT = pathlib.Path(tempfile.gettempdir()).resolve()


def executable(name):
    configured = os.environ.get("MP4_POSTGRES_BIN")
    candidates = [pathlib.Path(configured) / name] if configured else []
    found = shutil.which(name)
    if found:
        candidates.append(pathlib.Path(found))
    candidates.extend((pathlib.Path("/opt/homebrew/bin") / name,
                      pathlib.Path("/usr/local/bin") / name))
    for candidate in candidates:
        if candidate.is_file():
            return str(candidate)
    raise RuntimeError(f"Required PostgreSQL tool not found: {name}")


def free_port():
    with socket.socket() as sock:
        sock.bind(("127.0.0.1", 0))
        return sock.getsockname()[1]


def child_environment():
    env = os.environ.copy()
    for key in tuple(env):
        if key.upper().startswith(("PG", "MP4_AUTH_", "SPRING_")):
            env.pop(key, None)
    return env


def request_ready(url):
    try:
        with urllib.request.urlopen(url, timeout=1) as response:
            return response.status == 200
    except (OSError, urllib.error.URLError):
        return False


def main():
    initdb, pg_ctl, createdb = (executable(name) for name in ("initdb", "pg_ctl", "createdb"))
    required = (
        CLASSES / "mp4/platform/AuthApplication.class",
        LIB,
        STATIC / "index.html",
        STATIC / "developer/index.html",
        STATIC / "assets/revision.txt",
    )
    for path in required:
        if not path.exists():
            raise RuntimeError(f"Packaged distribution input missing: {path.relative_to(ROOT)}")
    smoke = ROOT / "src/backend/platform/deployment/test/developer-static-packaging-smoke.py"
    if not smoke.is_file():
        raise RuntimeError("Developer packaged smoke test is missing")

    scratch = pathlib.Path(tempfile.mkdtemp(prefix="mp4-packaged-smoke-")).resolve()
    if not scratch.is_relative_to(TEMP_ROOT) or scratch == TEMP_ROOT:
        raise RuntimeError("Resolved scratch directory escaped the system temporary directory")
    data = scratch / "postgres-data"
    password_file = scratch / "postgres-password"
    postgres_log = scratch / "postgres.log"
    app_log = scratch / "application.log"
    db_password = secrets.token_urlsafe(32)
    limiter_secret = secrets.token_urlsafe(48)
    db_port = free_port()
    app_port = free_port()
    postgres_started = False
    app = None

    try:
        password_file.write_text(db_password, encoding="utf-8")
        password_file.chmod(0o600)
        subprocess.run(
            [initdb, "-D", str(data), "-U", "postgres", "--pwfile", str(password_file),
             "--auth-local=scram-sha-256", "--auth-host=scram-sha-256",
             "--encoding=UTF8", "--no-locale"],
            stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True,
        )
        password_file.unlink(missing_ok=True)

        postgres_options = f"-h 127.0.0.1 -p {db_port} -k {scratch}"
        subprocess.run([pg_ctl, "-D", str(data), "-l", str(postgres_log),
                        "-o", postgres_options, "-w", "start"],
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        postgres_started = True

        db_env = child_environment()
        db_env.update({
            "PGHOST": "127.0.0.1",
            "PGPORT": str(db_port),
            "PGUSER": "postgres",
            "PGPASSWORD": db_password,
        })
        subprocess.run([createdb, "-h", "127.0.0.1", "-p", str(db_port), "-U", "postgres",
                        "mp4_packaged_smoke"], env=db_env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)

        app_env = child_environment()
        app_env.update({
            "MP4_AUTH_JDBC_URL": f"jdbc:postgresql://127.0.0.1:{db_port}/mp4_packaged_smoke",
            "MP4_AUTH_DB_USER": "postgres",
            "MP4_AUTH_DB_PASSWORD": db_password,
            "MP4_AUTH_RATE_LIMIT_SECRET": limiter_secret,
            "MP4_AUTH_LOCAL": "true",
            "SERVER_ADDRESS": "127.0.0.1",
            "SERVER_PORT": str(app_port),
        })
        classpath = f"{CLASSES}{os.pathsep}{LIB}/*"
        with app_log.open("wb") as log:
            app = subprocess.Popen(
                ["java", "--add-modules=jdk.httpserver", "-cp", classpath,
                 "mp4.platform.AuthApplication", "--server.address=127.0.0.1",
                 f"--server.port={app_port}"],
                cwd=DIST, env=app_env, stdout=log, stderr=subprocess.STDOUT,
                start_new_session=True,
            )

        origin = f"http://127.0.0.1:{app_port}"
        deadline = time.monotonic() + 45
        while time.monotonic() < deadline:
            if app.poll() is not None:
                raise RuntimeError("Packaged application exited before becoming healthy")
            if request_ready(origin + "/api/health"):
                break
            time.sleep(0.25)
        else:
            raise RuntimeError("Packaged application did not become healthy within 45 seconds")

        smoke_env = child_environment()
        smoke_env.update({
            "MP4_PACKAGED_ORIGIN": origin,
            "MP4_EXPECTED_REVISION": (STATIC / "assets/revision.txt").read_text(encoding="utf-8").strip(),
        })
        subprocess.run([sys.executable, str(smoke)], cwd=ROOT, env=smoke_env, check=True)
    finally:
        if app is not None and app.poll() is None:
            app.terminate()
            try:
                app.wait(timeout=10)
            except subprocess.TimeoutExpired:
                app.kill()
                app.wait(timeout=5)
        if data.exists() and (postgres_started or (data / "postmaster.pid").exists()):
            subprocess.run([pg_ctl, "-D", str(data), "-m", "fast", "-w", "stop"],
                           stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=False)
        if data.exists() and (data / "postmaster.pid").exists():
            raise RuntimeError("Refusing cleanup because the owned PostgreSQL instance is still running")
        resolved = scratch.resolve()
        if not resolved.is_relative_to(TEMP_ROOT) or resolved == TEMP_ROOT:
            raise RuntimeError("Refusing cleanup outside the system temporary directory")
        shutil.rmtree(resolved, ignore_errors=False)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Packaged smoke runner failed: {type(error).__name__}", file=sys.stderr)
        sys.exit(1)
