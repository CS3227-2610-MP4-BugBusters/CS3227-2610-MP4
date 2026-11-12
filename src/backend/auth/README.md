# Accounts and provisioning

Added `/account/`, linked from every role. A signed-in Maintainer can create
Reporter, Developer and Maintainer accounts with a username, display name and
initial password. Ordinary accounts can change their own password by supplying
the current password and a new 12–72 UTF-8 byte password. A successful change
invalidates all sessions; sign in again afterward.

Seeded this fixed public demo account at application startup:

- Username: `demo.maintainer`
- Display name: `Demo Maintainer`
- Password: `demo-maintainer-2026`

Kept its name, password, role and active state immutable, including through the
operator commands. Restarting never overwrites another account or resets ordinary
passwords. The auth V103 migration adds the database protection. The operator
command remains available for creating and recovering ordinary accounts.

## Operator command

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

The dedicated `mp4_auth_schema_history` table owns auth migrations V100–V103 and baselines a nonempty schema at 99. Account insertion is a separate transaction; success is printed only after commit. Existing rows are never replaced. Output is one JSON line: `ACCOUNT_CREATED` (exit 0), `VALIDATION_FAILED` (2), `ACCOUNT_EXISTS` (3), or `STORAGE_UNAVAILABLE` (4). Gradle may itself return its build-failure status for a nonzero Java process exit; the JSON code records the command result. Never put secrets in shell history, command arguments, URLs, logs or committed files.


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

### Local Developer AI configuration

From the repository root, copy `.env.example` to `.env` and set
`SOC_LLM_ENABLED=true`, `SOC_LLM_MODEL`, `SOC_LLM_RPM`, `SOC_LLM_TPM` and
`SOC_LLM_API_KEY_DEVELOPER` in that local file. Use the team-approved shared
gateway model and budget values. The example contains no credentials, and
`.env` is ignored by Git. This file uses
shell assignment syntax: quote values containing shell-special characters and
only source a file you trust. Avoid putting credentials in terminal commands or
sharing the file.

Optionally set `SOC_LLM_MODEL_DEVELOPER=llama3.1:8b` to use that model only for
Developer suggestions. An absent or blank override falls back to `SOC_LLM_MODEL`;
Reporter and Maintainer continue using the shared setting. Confirm model access
and team policy before deployment.

The Java application does not automatically load `.env`. On macOS or Linux,
load it into the shell before starting the backend:

```sh
set -a
source .env
set +a
cd src/backend/auth
./gradlew runAuth
```

The existing database and rate-limit environment variables listed above are
also required to start the backend. Configure the SoC values as environment
secrets separately in each deployed environment; do not commit real values.
Developer suggestions use the shared role gateway, its PostgreSQL admission
limits, and the configured Developer key. A key alone does not establish live
provider availability or quota.

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
actor to the existing assigned-report handler and store; public signup and role changes are not exposed. Added `GET /api/account`,
`POST /api/maintainer/accounts` and `POST /api/account/password`; writes use
JSON, the current session and `X-CSRF-TOKEN`. Password changes accept only
`currentPassword` and `newPassword`, never a target account ID.
