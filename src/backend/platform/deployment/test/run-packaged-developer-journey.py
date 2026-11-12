#!/usr/bin/env python3
"""Exercise the packaged app over HTTP with real sessions and disposable PostgreSQL."""

import http.cookiejar
import json
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
import urllib.parse
import urllib.request
import uuid


ROOT = pathlib.Path(__file__).resolve().parents[5]
AUTH = ROOT / "src/backend/auth"
DIST = ROOT / "src/backend/auth/build/auth-distribution"
CLASSES = DIST / "classes"
LIB = DIST / "lib"
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


def clean_environment():
    env = os.environ.copy()
    for key in tuple(env):
        if key.upper().startswith(("PG", "MP4_AUTH_", "SPRING_")):
            env.pop(key, None)
    return env


def run_quiet(command, *, env=None, cwd=None, input_bytes=None):
    result = subprocess.run(command, cwd=cwd, env=env, input=input_bytes,
                            stdout=subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode != 0:
        raise RuntimeError(f"Disposable setup command failed: {pathlib.Path(command[0]).name}")
    return result.stdout.decode("utf-8", errors="replace")


def provision_account(classpath, db_env, role, display_name):
    account_id = str(uuid.uuid4())
    username = f"journey.{role.lower()}.{secrets.token_hex(4)}"
    password = secrets.token_urlsafe(24)
    command = [
        "java", "--add-modules=jdk.httpserver", "-cp", classpath,
        "mp4.auth.AccountProvisioningCommand", "create", "--username", username,
        "--display-name", display_name, "--role", role, "--password-stdin",
        "--id", account_id,
    ]
    output = run_quiet(command, env=db_env, input_bytes=password.encode("utf-8"))
    if password in output:
        raise RuntimeError("Operator provisioning output disclosed generated password")
    responses = [line for line in output.splitlines() if line.startswith("{")]
    if not responses or json.loads(responses[-1]).get("code") != "ACCOUNT_CREATED":
        raise RuntimeError(f"Disposable {role} account provisioning failed")
    if json.loads(responses[-1]).get("id") != account_id:
        raise RuntimeError("Operator provisioning returned an unexpected account ID")
    return {"id": account_id, "username": username, "password": password, "role": role}


class HttpBrowser:
    """Cookie-preserving HTTP client; this runner does not drive a browser UI."""

    def __init__(self, origin):
        self.origin = origin
        self.opener = urllib.request.build_opener(
            urllib.request.HTTPCookieProcessor(http.cookiejar.CookieJar()))

    def request(self, method, path, body=None, csrf=None, content_type=None):
        headers = {"Accept": "application/json"}
        if content_type:
            headers["Content-Type"] = content_type
        if csrf is not None:
            headers["X-CSRF-TOKEN"] = csrf
        payload = None if body is None else body.encode("utf-8")
        req = urllib.request.Request(self.origin + path, data=payload,
                                    headers=headers, method=method)
        try:
            with self.opener.open(req, timeout=15) as response:
                return response.status, response.read()
        except urllib.error.HTTPError as response:
            return response.code, response.read()

    def json_request(self, method, path, value=None, csrf=None):
        body = None if value is None else json.dumps(value, separators=(",", ":"))
        status, raw = self.request(method, path, body, csrf,
                                   "application/json" if value is not None else None)
        try:
            return status, json.loads(raw.decode("utf-8"))
        except (UnicodeDecodeError, json.JSONDecodeError) as error:
            raise AssertionError(f"{method} {path} did not return JSON") from error

    def fresh_csrf(self):
        status, body = self.json_request("GET", "/api/auth/csrf")
        assert status == 200, "CSRF endpoint should allow unauthenticated session bootstrap"
        assert body.get("token") and body.get("headerName") == "X-CSRF-TOKEN"
        return body["token"]

    def login(self, account):
        token = self.fresh_csrf()
        form = urllib.parse.urlencode({"username": account["username"],
                                       "password": account["password"]})
        status, raw = self.request("POST", "/api/auth/login", form, token,
                                   "application/x-www-form-urlencoded")
        assert status == 200, f"{account['role']} login should succeed (HTTP {status})"
        user = json.loads(raw.decode("utf-8"))
        assert user.get("role") == account["role"] and user.get("active") is True
        assert user.get("id") == account["id"]


def assert_http_error(status, body, expected_status, expected_code):
    assert status == expected_status, f"expected HTTP {expected_status}, got {status}: {body}"
    assert body.get("code") == expected_code, f"expected {expected_code}, got {body}"


def wait_ready(process, origin):
    deadline = time.monotonic() + 45
    while time.monotonic() < deadline:
        if process.poll() is not None:
            raise RuntimeError("Packaged application exited before becoming healthy")
        try:
            with urllib.request.urlopen(origin + "/api/health", timeout=1) as response:
                if response.status == 200:
                    return
        except (OSError, urllib.error.URLError):
            time.sleep(0.25)
    raise RuntimeError("Packaged application did not become healthy within 45 seconds")


def psql(psql_bin, env, database, sql, file=None):
    command = [psql_bin, "-v", "ON_ERROR_STOP=1", "-X", "-q", "-d", database]
    command += ["-f", str(file)] if file else ["-At", "-c", sql]
    return run_quiet(command, env=env)


def assert_database_state(psql_bin, env, report_id, *, version, draft_revision, draft, shared):
    sql = (
        "SELECT json_build_object('version',r.version,'draftRevision',c.draft_revision,"
        "'draft',to_json(c.draft_steps),'shared',to_json(c.shared_steps)) "
        f"FROM reports r JOIN investigation_checklists c ON c.report_id=r.id WHERE r.id='{report_id}'"
    )
    raw = psql(psql_bin, env, "mp4_developer_journey", sql).strip()
    state = json.loads(raw)
    assert state == {"version": version, "draftRevision": draft_revision,
                     "draft": draft, "shared": shared}, f"unexpected persisted checklist state: {state}"


def exercise_journey(origin, developer, other_developer, reporter, report_id, psql_bin, db_env):
    report_path = f"/api/reports/{report_id}/checklist/draft"
    share_path = f"/api/reports/{report_id}/checklist/share"
    shared_path = f"/api/reports/{report_id}/checklist/shared"

    dev = HttpBrowser(origin)
    dev.login(developer)
    status, listing = dev.json_request("GET", "/api/developer/reports")
    assert status == 200 and listing == {
        "items": [{"id": report_id, "title": "Packaged journey report", "status": "ASSIGNED"}],
        "page": 1, "pageSize": 20, "totalItems": 1,
    }, f"Developer assigned list was unexpected: {listing}"

    no_csrf_status, no_csrf_body = dev.json_request(
        "PUT", report_path, {"steps": ["must not persist"], "expectedDraftRevision": 0})
    assert_http_error(no_csrf_status, no_csrf_body, 403, "CSRF_INVALID")
    assert_database_state(psql_bin, db_env, report_id, version=0, draft_revision=0,
                          draft=[], shared=[])

    other = HttpBrowser(origin)
    other.login(other_developer)
    denied_read_status, denied_read = other.json_request("GET", report_path)
    assert_http_error(denied_read_status, denied_read, 403, "FORBIDDEN")
    denied_save_status, denied_save = other.json_request(
        "PUT", report_path, {"steps": ["must not persist"], "expectedDraftRevision": 0},
        other.fresh_csrf())
    assert_http_error(denied_save_status, denied_save, 403, "FORBIDDEN")
    denied_share_status, denied_share = other.json_request(
        "POST", share_path, {"expectedDraftRevision": 0, "expectedReportVersion": 0},
        other.fresh_csrf())
    assert_http_error(denied_share_status, denied_share, 403, "FORBIDDEN")
    assert_database_state(psql_bin, db_env, report_id, version=0, draft_revision=0,
                          draft=[], shared=[])

    status, draft = dev.json_request("GET", report_path)
    assert status == 200 and draft == {"steps": [], "draftRevision": 0, "reportVersion": 0}, draft

    steps = ["Reproduce the export failure", "Capture the request trace"]
    status, saved = dev.json_request(
        "PUT", report_path, {"steps": steps, "expectedDraftRevision": 0}, dev.fresh_csrf())
    assert status == 200 and saved == {"draftRevision": 1}, saved
    assert_database_state(psql_bin, db_env, report_id, version=0, draft_revision=1,
                          draft=steps, shared=[])

    status, shared = dev.json_request(
        "POST", share_path, {"expectedDraftRevision": 1, "expectedReportVersion": 0},
        dev.fresh_csrf())
    assert status == 200 and shared == {"reportVersion": 1, "changed": True}, shared
    assert_database_state(psql_bin, db_env, report_id, version=1, draft_revision=1,
                          draft=steps, shared=steps)

    reporter_browser = HttpBrowser(origin)
    reporter_browser.login(reporter)
    status, published = reporter_browser.json_request("GET", shared_path)
    assert status == 200 and published == {"steps": steps}, published


def main(exercise=exercise_journey, success_message="Packaged Developer real-session HTTP journey passed (not a browser check)."):
    initdb, pg_ctl, createdb, psql_bin = (executable(name) for name in
                                          ("initdb", "pg_ctl", "createdb", "psql"))
    required = (
        CLASSES / "mp4/platform/AuthApplication.class",
        CLASSES / "mp4/auth/AccountProvisioningCommand.class",
        LIB,
    )
    for path in required:
        if not path.exists():
            raise RuntimeError(f"Journey setup input missing: {path.relative_to(ROOT)}")

    scratch = pathlib.Path(tempfile.mkdtemp(prefix="mp4-packaged-journey-")).resolve()
    if not scratch.is_relative_to(TEMP_ROOT) or scratch == TEMP_ROOT:
        raise RuntimeError("Resolved scratch directory escaped system temporary directory")
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
        pg_options = f"-h 127.0.0.1 -p {db_port} -k {scratch}"
        subprocess.run([pg_ctl, "-D", str(data), "-l", str(postgres_log), "-o", pg_options,
                        "-w", "start"], stdout=subprocess.DEVNULL,
                       stderr=subprocess.DEVNULL, check=True)
        postgres_started = True

        db_env = clean_environment()
        db_env.update({"PGHOST": "127.0.0.1", "PGPORT": str(db_port),
                       "PGUSER": "postgres", "PGPASSWORD": db_password})
        subprocess.run([createdb, "-h", "127.0.0.1", "-p", str(db_port), "-U", "postgres",
                        "mp4_developer_journey"], env=db_env,
                       stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL, check=True)
        db_user_env = clean_environment()
        jdbc_url = f"jdbc:postgresql://127.0.0.1:{db_port}/mp4_developer_journey"
        db_user_env.update({"MP4_AUTH_JDBC_URL": jdbc_url,
                            "MP4_AUTH_DB_USER": "postgres",
                            "MP4_AUTH_DB_PASSWORD": db_password})
        classpath = f"{CLASSES}{os.pathsep}{LIB}/*"
        developer = provision_account(classpath, db_user_env, "DEVELOPER", "Journey Developer")
        other_developer = provision_account(classpath, db_user_env, "DEVELOPER", "Other Developer")
        reporter = provision_account(classpath, db_user_env, "REPORTER", "Journey Reporter")

        app_env = clean_environment()
        app_env.update({"MP4_AUTH_JDBC_URL": jdbc_url,
                        "MP4_AUTH_DB_USER": "postgres",
                        "MP4_AUTH_DB_PASSWORD": db_password,
                        "MP4_AUTH_RATE_LIMIT_SECRET": limiter_secret,
                        "MP4_AUTH_LOCAL": "true",
                        "SERVER_ADDRESS": "127.0.0.1",
                        "SERVER_PORT": str(app_port)})
        application_classpath = f"{CLASSES}{os.pathsep}{LIB}/*"
        with app_log.open("wb") as log:
            app = subprocess.Popen(
                ["java", "--add-modules=jdk.httpserver", "-cp", application_classpath,
                 "mp4.platform.AuthApplication", "--server.address=127.0.0.1",
                 f"--server.port={app_port}"],
                cwd=DIST, env=app_env, stdout=log, stderr=subprocess.STDOUT,
                start_new_session=True,
            )
        origin = f"http://127.0.0.1:{app_port}"
        wait_ready(app, origin)
        report_id = str(uuid.uuid4())
        fixture = (
            "INSERT INTO reports(id,title,description,reporter_id,assignee_id,status,version) VALUES "
            f"('{report_id}','Packaged journey report','Disposable HTTP journey fixture',"
            f"'{reporter['id']}','{developer['id']}','ASSIGNED',0); "
            "INSERT INTO investigation_checklists(report_id,draft_steps,draft_revision,shared_steps) VALUES "
            f"('{report_id}',ARRAY[]::text[],0,ARRAY[]::text[]);"
        )
        psql(psql_bin, db_env, "mp4_developer_journey", fixture)
        exercise(origin, developer, other_developer, reporter, report_id, psql_bin, db_env)
        print(success_message)
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
            raise RuntimeError("Refusing cleanup outside system temporary directory")
        shutil.rmtree(resolved, ignore_errors=False)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"Packaged Developer journey failed: {type(error).__name__}", file=sys.stderr)
        sys.exit(1)
