# Authentication module setup control

Prepared this setup control to verify that the pinned Gradle wrapper can resolve the
Java 17 auth module against an isolated PostgreSQL instance, that the existing
shared `reports` and Developer `investigation_checklists` migrations apply, and
that Spring Security's password encoder and Spring Session JDBC classes are on
the test classpath. Have not implemented or verified authentication behaviour.

## Local command

From the repository root run:

```powershell
python src/backend/auth/test/run_setup.py
```

Prerequisites: Python 3.10+, JDK 17 or newer (Java 17 compilation target), network access for first Gradle dependency
resolution, and PostgreSQL client/server binaries (locally verified with 17) (`initdb`, `pg_ctl`, `psql`).
Set `MP4_POSTGRES_BIN` to their `bin` directory or make them available on `PATH`.
The runner also checks the standard PostgreSQL 17 Windows install path. On Linux,
the tools must be on `PATH` or configured explicitly.

The runner creates a private loopback-only cluster and database in a fresh system
temporary directory, applies the shared migrations through Flyway in the setup control,
invokes `gradlew --no-daemon check`,
then stops and removes only its own cluster and scratch directory. It never uses
an externally configured database. The generated database password is passed via
environment, not command arguments, and is not printed. Missing tools fail the
run; the setup is not silently skipped. On POSIX systems the PostgreSQL password
file is restricted to the current user and removed immediately after initialization.
The server log and Unix socket live inside the private scratch directory. Cleanup
refuses to remove the scratch tree if shutdown fails or the owned process is live.

Run the same Python command in CI with JDK 17+, Python 3.10+ and PostgreSQL
server/client binaries installed, and may cache Gradle artifacts. This
initial setup control does not verify wrapper checksum policy, application wiring,
auth migrations, HTTP behavior, real sessions, or feature authorization. Those
remain later task requirements.

## Dependency and source notes

Pinned Spring Boot's 4.0.9 BOM, Spring MVC/embedded Tomcat, Spring Security,
Spring Session JDBC, PostgreSQL and Flyway for test-classpath setup. Generated the
Gradle 9.1.0 wrapper from the official distribution; verified the distribution and
wrapper JAR SHA-256 against `services.gradle.org`. Preserve its Apache license
headers and acknowledge Gradle/Spring/PostgreSQL in the final Developer Guide.
Used MP3 only to identify framework/server choices, not as an implementation source.

The wrapper properties pin distribution integrity. The control checks library
availability, not an actual Spring server, JDBC session or Flyway migration history.
Disabled Gradle test result reuse because each run supplies a different temporary
database. Missing settings fail rather than skip the test. If JAVA_HOME points to
an old installation, set it to a valid JDK for the shell running this command; no
system-wide environment change is required.

## Repository CI

Added an `Auth setup` matrix for Java 17 and 21 using this same Python runner.
The job installs Ubuntu's PostgreSQL package, prints its actual version and checks
the pinned wrapper JAR before running. Local evidence uses PostgreSQL 17 on Windows;
have not inferred Linux or Java 17 runtime results from that run. The CI job is a
setup check and does not establish provisioning, login or shared Flyway registration.
