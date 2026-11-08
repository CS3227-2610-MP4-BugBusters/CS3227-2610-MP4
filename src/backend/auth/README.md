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

The dedicated `mp4_auth_schema_history` table owns V100 only and baselines a nonempty schema at 99. Account insertion is a separate transaction; success is printed only after commit. Existing rows are never replaced. Output is one JSON line: `ACCOUNT_CREATED` (exit 0), `VALIDATION_FAILED` (2), `ACCOUNT_EXISTS` (3), or `STORAGE_UNAVAILABLE` (4). Gradle may itself return its build-failure status for a nonzero Java process exit; the JSON code records the command result. Never put secrets in shell history, command arguments, URLs, logs or committed files.


## Verified scope

Implemented account creation and duplicate rejection against PostgreSQL. Validate
passwords through Spring's BCrypt encoder; no plaintext account password is stored.
Use the disposable runner in `test/README.md` for acceptance and validation checks.
Have not added login, sessions, reset/disable, a provisioning web endpoint or shared
application startup wiring. Creating an account does not yet provide browser login.

Keep db/auth resources on the dedicated auth migration history; do not also register them under a shared Flyway history. Shared application startup integration needs its own reviewed migration handoff.
