# Account provisioning

`provisionAccount` is an operator-only create command. It reads password bytes from standard input through EOF and preserves every byte, including a final newline. Supply 12–72 valid UTF-8 bytes; values are never trimmed. The CLI arguments contain no password. The command accepts only `create --username VALUE --display-name VALUE --role REPORTER|DEVELOPER|MAINTAINER --password-stdin`, with optional `--id UUID` for fixtures.

Run from `src/backend/auth`. Configure `MP4_AUTH_JDBC_URL`, `MP4_AUTH_DB_USER` and `MP4_AUTH_DB_PASSWORD` in the operator environment. The JDBC URL must be `jdbc:postgresql://HOST:PORT/DATABASE`, without URL credentials or query parameters. This PowerShell example prompts privately and sends the UTF-8 password bytes directly to Gradle's standard input; the secret never enters command arguments or shell history:

```powershell
$env:MP4_AUTH_JDBC_URL = 'jdbc:postgresql://127.0.0.1:5432/mp4'
$env:MP4_AUTH_DB_USER = 'mp4_operator'
@'
import getpass, subprocess
secret = getpass.getpass('New account password: ')
args = 'create --username reporter.one --display-name "Reporter One" --role REPORTER --password-stdin'
result = subprocess.run(['.\\gradlew.bat', '--quiet', '--no-daemon', 'provisionAccount', '--args=' + args], input=secret.encode('utf-8'))
raise SystemExit(result.returncode)
'@ | python -
```

The dedicated `mp4_auth_schema_history` table owns auth migrations V100–V102 and baselines a nonempty schema at 99. Account insertion is a separate transaction; success is printed only after commit. Existing rows are never replaced. Output is one JSON line: `ACCOUNT_CREATED` (exit 0), `VALIDATION_FAILED` (2), `ACCOUNT_EXISTS` (3), or `STORAGE_UNAVAILABLE` (4). Gradle may itself return its build-failure status for a nonzero Java process exit; the JSON code records the command result. Never put secrets in shell history, command arguments, URLs, logs or committed files.


## Recovery operations

The operator command supports `disable --username VALUE` and
`reset-password --username VALUE --password-stdin`. Reset reads a 12–72 byte
UTF-8 password from stdin. Disable preserves the hash and identity, marks the
account inactive and increments its authentication generation; repeated disable
also increments it. Reset changes only the hash and generation, preserving
active/inactive state. Existing sessions fail on their next protected request.
Both operations return only `{code,id,role}` after commit. Unknown accounts
return `ACCOUNT_NOT_FOUND` (exit 6); invalid input is `VALIDATION_FAILED` (2),
and storage failures are `STORAGE_UNAVAILABLE` (4).

## Web application

Run `runAuth` with `MP4_AUTH_JDBC_URL`, `MP4_AUTH_DB_USER`,
`MP4_AUTH_DB_PASSWORD` and `MP4_AUTH_RATE_LIMIT_SECRET`. The HMAC secret must be
at least 32 UTF-8 bytes; there is no production default. `mp4.auth.local=true`
allows an insecure cookie only for a loopback bind. Otherwise `MP4SESSION` is
Secure, HttpOnly, SameSite=Lax, host-only and Path=/ . `mp4.auth.public-origin`
can name the exact browser origin when an external proxy changes the public
origin; forwarded headers are ignored.

Browser flow: call `GET /api/auth/csrf`, send its `X-CSRF-TOKEN` on the
URL-encoded `POST /api/auth/login`, then fetch a fresh CSRF token after login.
The successful login returns `{id,displayName,role,active}` and rotates the
pre-login session identifier. `GET /api/auth/me` returns that safe projection.
Send a fresh CSRF token on the empty-body `POST /api/auth/logout`; logout
invalidates the server session. Keep CSRF tokens in memory.

JDBC-backed sessions and shared rolling login budgets use the auth-owned
migration history. Budgets allow 10 attempts per normalized username and 100
per client address in 15 minutes, with 24-hour retention cleanup. Authentication
and protected access fail closed when account, session or limiter storage is
unavailable. The real `/api/developer/reports` route passes the current session
actor to the existing assigned-report handler and store; account signup, web
password reset and role changes are not exposed.
