# Disposable PostgreSQL setup restored

The gateway port needs real PostgreSQL checks for durable quota behavior. PostgreSQL
server executables were initially unavailable. Downloaded the official portable
Windows archive into a task-specific system temporary directory, extracted the
server tools, and ran the repository's existing disposable database setup control.
The control passed. The runner created and cleaned up its own loopback database;
no application database, persistent system service or repository runner was changed.

## Technical evidence

- Date: 2026-11-10, Asia/Singapore; task SOC-MP4-PORT.
- Tool source: [EDB PostgreSQL binaries](https://www.enterprisedb.com/download-postgresql-binaries).
- Page's PostgreSQL 17 Windows link resolved via HTTPS to
  `https://get.enterprisedb.com/postgresql/postgresql-17.11-5-windows-x64-binaries.zip`.
- Local downloaded archive SHA-256:
  `80379b2c04d51c30225532e0ae04509899141e9957ed096fe749d7fd9df8f82f`.
  This records the downloaded bytes; it is not a separately verified upstream checksum.
- Tools: `%TEMP%/mp4-socllm-pg17-tools/pgsql/bin`; extracted bin/lib/share only,
  checking extracted paths stay under that temporary directory.
- Environment for the test process: `MP4_POSTGRES_BIN` set to that bin directory.
- Check from repository root:
  `python src/backend/auth/test/run_setup.py --tests mp4.maintainer.summary.SummarySetupTest`.
- Result: exit 0, Gradle BUILD SUCCESSFUL in 10 seconds, 5 tasks (2 executed,
  3 up-to-date). The setup test passed; test execution is not cached by the runner.
- Elevated execution was needed to use the existing Gradle cache and run the
  isolated PostgreSQL process. No permission, dependency or runner policy changed.
- This resolves the earlier missing-initdb blocker; it does not itself verify the
  candidate's quota SQL or live provider connectivity.
- After verification, removed only the downloaded ZIP after rechecking its recorded
  SHA-256. The extracted portable tools remain available for repeatable local checks.
