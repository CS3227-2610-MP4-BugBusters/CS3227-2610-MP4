# Repository verification

Select the appropriate development process in [workflow selection](workflow-selection.md).
Use the [integration and release checklist](integration-release-checklist.md)
to record shared-app and deployment evidence before release.

Run the standard checks from the repository root with:

```sh
python workflow/verify.py
```

The default command runs both frontends, the isolated authentication setup,
release security scans, deployment and runner checks, strict OpenSpec validation,
GitHub Actions lint, and `git diff --check`. A child command that fails stops the
runner and returns a nonzero status. Missing tools, unavailable network access,
scanner findings, and checksum failures remain failures.

Use `--group frontend`, `--group backend`, `--group security`, or `--group tooling`
to run one group. The frontend group accepts `--frontend developer` or
`--frontend maintainer` to select one app. Each frontend needs Node.js and its
lockfile dependencies installed with `npm ci`. The backend setup checks the pinned
Gradle wrapper JAR before it runs, and needs Java and PostgreSQL client/server
tools as described in `src/backend/auth/README.md`. Security scans download pinned tools into the
external user cache and need network access; their exact checks and limits are
documented in `workflow/security/README.md`. The tooling group needs the pinned
OpenSpec CLI installed and also obtains pinned actionlint through the security
tool helper.

GitHub Actions uses the same entry point for frontend, authentication, security,
and tooling jobs. The assigned reports job retains its separate Java/PostgreSQL
matrix and database integration checks. Image build/startup and shared-storage
SQL checks remain separate CI checks because they need Docker or their own
database setup.

Use `--group fixture --fixture-root /tmp/isolated-case` for agent-evaluation fixtures.
This uses the same checked entry point with the frozen fixture's unittest command,
requires its SPEC/test_contract files and passes only runtime-path environment
variables. It remains a development fixture runner, not an OS sandbox. The default
release command excludes these deliberately failing scenarios; tooling tests check
their failure/scope auditor instead.
