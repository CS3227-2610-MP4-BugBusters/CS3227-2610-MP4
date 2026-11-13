# BugBusters developer guide

## 1. Introduction

BugBusters is a programming-related bug tracker with Reporter, Developer and Maintainer workspaces. This guide covers local setup, the application boundaries, verification and deployment preparation. See the [User Guide](UserGuide.md) for role workflows.

This guide describes the integrated main branch at `03b415b` and identifies pending changes where applicable. The local checkout may be older or contain additional uncommitted work. Check `git status`, `git log` and the deployed revision before relying on a feature. A hosted deployment was not verified for this update.

## 2. Technology and repository layout

| Area | Location / technology |
| --- | --- |
| Role clients | `src/frontend/reporter`, `developer`, `maintainer`: React, TypeScript and Vite |
| Shared UI | `src/frontend/shared`, including the Markdown package |
| Backend | Java 17, Spring Boot/Security and Gradle wrapper; entry point in `src/backend/platform` and build in `src/backend/auth` |
| Persistence | PostgreSQL and Flyway; auth, shared and SoC histories are separate |
| Role services | `src/backend/reporter`, `developer`, `maintainer`, and `discussion` |
| AI | `src/backend/socllm` and role-specific gateway adapters in `platform` |
| Requirements and contracts | `openspec/` and `workflow/` |
| Verification and evidence | `workflow/verify.py`, `workflow/security/`, `logs/` and `.github/workflows/` |
| Product documentation | `docs/UserGuide.md` and this guide |

The browser sends requests to a single Spring application. The server derives the actor from its session, checks access and delegates to role services and PostgreSQL. State-changing requests use CSRF protection. The shared SoC gateway is server-side; generated text is untrusted and requires role-specific validation and user review.

```mermaid
flowchart LR
    R[Reporter UI] --> API[Spring application]
    D[Developer UI] --> API
    M[Maintainer UI] --> API
    API --> DB[(PostgreSQL)]
    API --> AI[SoC gateway]
    AI --> Provider[Configured SoC provider]
```

## 3. Local setup

### Prerequisites

Install Java 17, Node.js and npm, Python 3, and PostgreSQL. The frontend deployment image uses Node 22; OpenSpec guidance recommends Node 24 or a compatible version. Database integration runners need PostgreSQL client and server tools, and some Reporter tests need Docker and a PostgreSQL JDBC JAR. See the [Reporter backend README](../src/backend/reporter/README.md) for its exact prerequisites.

Use a **new disposable database** and synthetic accounts. Do not point test runners at production or a shared development database. Startup applies migrations; see [database compatibility](#5-database-and-migrations) before using existing data.

### Configuration

1. Set `MP4_AUTH_JDBC_URL`, `MP4_AUTH_DB_USER`, `MP4_AUTH_DB_PASSWORD`, and `MP4_AUTH_RATE_LIMIT_SECRET` in the backend's environment. The JDBC URL has the form `jdbc:postgresql://HOST:PORT/DATABASE` without credentials or query parameters. Use a rate-limit secret of at least 32 UTF-8 bytes.
2. For HTTP on a loopback-only local backend, set `MP4_AUTH_LOCAL=true`. The backend defaults to `127.0.0.1:8080`; local cookies are rejected on non-loopback binds. Do not use this setting for a deployed service.
3. For an optional local AI configuration, copy `.env.example` to `.env` **only if you do not already have one**, then fill in the settings you need. The example defaults AI to disabled and contains no credentials. `.env` is ignored by Git and is not loaded automatically. Source only a trusted file; use shell quoting for values with special characters. The backend README shows how to load it into the shell. Keep real credentials out of commands, logs and version control.

The operator must provide database access and secrets. Main also seeds a publicly documented, immutable demo Maintainer for synthetic-data demonstrations; see [authentication configuration](../src/backend/auth/README.md). This public credential is unsuitable for an environment containing real or sensitive data. For AI settings see [SoC setup](#7-ai-integration).

### Provision accounts

From `src/backend/auth`, use `provisionAccount` with `create`, `--username`, `--display-name`, `--role REPORTER|DEVELOPER|MAINTAINER`, and `--password-stdin`. The command reads 12–72 UTF-8 password bytes through EOF, including any final newline. Follow the private-input example in the [auth README](../src/backend/auth/README.md); do not put the password in arguments or shell history. Create three separate synthetic accounts for a role walkthrough.

The same command supports `reset-password --username VALUE --password-stdin` and `disable --username VALUE`. A password reset or disable invalidates existing sessions; a reset does not reactivate a disabled account. There is no public registration or browser password-reset page. Check the returned JSON code before assuming an operation succeeded.

### Run the backend and role clients

With the disposable database settings and rate-limit secret in the environment:

```sh
cd src/backend/auth
./gradlew runAuth
```

For Vite development, the checked-in proxies forward `/api` to `127.0.0.1:8080`. The login endpoint checks the browser origin. For example, run **one** role client at `http://127.0.0.1:5173`, set `MP4_AUTH_PUBLIC_ORIGIN=http://127.0.0.1:5173` in the backend environment before starting it, and use the same host and port in the browser. Restart the backend if its configured public origin changes. Do not expose the local HTTP server publicly.

Install dependencies once per package and start the desired client from the repository root:

```sh
cd src/frontend/shared/markdown && npm ci
cd ../../reporter && npm ci
npm run dev -- --host 127.0.0.1 --port 5173 --strictPort
```

For Developer or Maintainer, instead run `npm ci` in `src/frontend/developer` or `src/frontend/maintainer`, then use `npm run dev -- --host 127.0.0.1 --port 5173 --strictPort` for Developer or `npm start -- --host 127.0.0.1 --port 5173 --strictPort` for Maintainer. Open the URL Vite prints; Reporter and Developer use `/reporter/` and `/developer/` bases respectively. The Developer checklist demo component and the explicitly selected Maintainer demo are isolated synthetic previews, not evidence of authenticated persistence. Use the normal entry with the backend for integration testing.

For an integrated local Vite session, run the normal Maintainer frontend at `127.0.0.1:5173`, Developer at `127.0.0.1:5174`, Reporter at `127.0.0.1:5175`, and the backend at `127.0.0.1:8080`; open the root URL on port 5173. The Maintainer Vite configuration proxies the two role routes to the matching ports. Set the backend public origin to the root browser origin. Main does not yet proxy `/account` in these Vite configurations; PR #27 proposes that fix and a display-name update. The packaged application serves `/account/` directly.

To build the packaged application, use the [deployment Dockerfile instructions](../src/backend/platform/deployment/README.md). The Dockerfile builds all three frontend bundles and the Java distribution; the packaged routes are `/`, `/developer/` and `/reporter/`. Its runtime binds to all interfaces and uses secure cookies; use an HTTPS setup rather than enabling loopback-only cookies in that container.

## 4. Application and security boundaries

| Area | Current responsibility |
| --- | --- |
| Authentication | Operator-provisioned fixed-role accounts, server sessions, sign-in/out and CSRF. Account state is checked on protected access. |
| Reporter | Create and read own reports, edit while Submitted and unassigned, participate in discussion, and request AI questions about a new draft. |
| Developer | Access assigned reports, start investigation, save private notes and checklist drafts, share saved checklists, request suggestions and submit resolutions. A separate shared investigation-note draft and publication flow is proposed in PR #26 and is not part of this baseline. |
| Maintainer | Browse reports, set priority and assignment, discuss, return or close a resolution, reopen, and request an advisory queue summary. |
| Shared storage | Versioned report mutations and lifecycle checks protect against stale changes; shared checklist copies are separate from private drafts. |

Reporter checklist responses, Developer discussion and AI assessment of responses
are not exposed by the screens inspected in this checkout. Reporter AI is
available through **Check my report** on new drafts. Planning documents and
isolated demos are not proof of a released feature; verify the target revision.

### Maintainer components and shared boundaries

The Maintainer frontend in `src/frontend/maintainer` uses typed same-origin HTTP adapters. `src/backend/maintainer` contains workflow rules, DTOs, JDBC repositories and bounded queue-summary processing; Spring controllers and session integration are under `src/backend/platform`. The summary uses the shared SoC gateway and database-backed admission controls. See the [Maintainer backend README](../src/backend/maintainer/README.md) for configuration.

Preserve the existing Developer checklist Save/Share contract when changing lifecycle behavior. Account-eligibility locks precede report locks; version checks, state changes and history are committed together. Private checklist saves do not change the report version. Auth and shared report migrations have separate histories; package the canonical SQL sources rather than maintaining duplicates.

Maintainer report descriptions use `src/frontend/shared/markdown` through `MarkdownParserProvider` and the shared Vite plugin. Other fields remain escaped text. Install the shared Markdown package's dependencies before the Maintainer package; tables and code blocks scroll within the description on narrow screens. See the [Maintainer frontend README](../src/frontend/maintainer/README.md).

### Reporter components and report workflow

| Component | Responsibility |
| --- | --- |
| `src/frontend/reporter/src` | `App` handles sign-in and hash routes; `ReportList`, `NewReport` and `ReportView` handle reports; `ReportCheck` displays AI questions; `api.ts` validates API responses. |
| `src/frontend/shared/markdown` | Shared Markdown editor, preview and saved-text renderer. |
| `src/backend/reporter/src` | `ReporterReportsHttpHandler` validates requests and routes to `ReporterReportStore` for JDBC persistence. |
| `src/backend/discussion` | Shared comment handling, strict JSON and text rules. |
| `src/backend/platform/src/main/java/mp4/platform` | Authenticated mounting, `ReportClarifyController`, service, rules, configuration and `SocLlmReportClarifyProvider`. |
| `src/backend/socllm` | Role routing, task policies, PostgreSQL admission and bounded provider transport. |

The server derives the Reporter identity from the session. Creation accepts
exactly `{title, description}`: a nonblank single-line title up to 200 UTF-16
code units and a nonblank description up to 20,000 units, with no NUL or unpaired
surrogates. The source text is stored without Markdown rendering. Creation is
limited to 3 reports per Reporter per rolling minute. Lists are newest first and
owner-only; another Reporter's report has the same 404 as a missing report.

Edits also supply `version` and require a Submitted, unassigned report.
`REPORT_LOCKED` and `VERSION_CONFLICT` prevent invalid or stale updates. Comments
are stored separately and do not increment the report version. Shared migration
`002_add_report_timestamps.sql` adds timestamps, and `003_report_comments.sql`
adds discussion storage; the packaged application uses their corresponding
Flyway migrations. See the [Reporter README](../src/backend/reporter/README.md)
for migration ordering and test-runner differences.

Descriptions and comments use the shared Markdown renderer, which escapes raw
HTML and displays images as alt text. AI results use React text nodes directly.

The session cookie is HttpOnly and SameSite=Lax, and Secure outside local loopback mode. Login rotates the session; clients obtain a CSRF token for writes. Do not trust a client-supplied role or actor identity. Treat report text, comments, checklist text and AI outputs as untrusted input. Consult the relevant contracts and security tests when changing authorization, rendering or AI boundaries.

## 5. Database and migrations

Startup initializes Flyway histories for auth (`mp4_auth_schema_history`), shared reports (`mp4_shared_schema_history`) and SoC (`mp4_socllm` schema). Auth migrations start at V100; shared migrations are assembled from shared, Maintainer, Reporter and Developer sources by the Gradle build. Inspect the current files and history rather than assuming version numbers from another branch.

Use a fresh database for new local work. Existing manual or older branch schemas need compatibility review before startup; a successful fresh install does not prove an existing-data upgrade. The shared bootstrap checks canonical schema compatibility, including defaults. Do not delete or rewrite Flyway history, force `repair`, reset a shared database, or run destructive reverse migrations to make an incompatible database start. Back up and design a data-preserving migration with the schema owners first. See the [Maintainer backend README](../src/backend/maintainer/README.md) and [shared storage README](../src/backend/shared/README.md).

## 6. Verification

A documented command is not a passing result. Record the revision, environment, exact command, result and any blocked checks for the work being reviewed.

### Frontend checks

After installing each package's dependencies:

```sh
cd src/frontend/developer && npm run verify
cd ../maintainer && npm run verify
cd ../shared/markdown && npm run verify
cd ../../reporter && npm run verify
```

Each `verify` runs type checks and tests; the role packages also build, while the shared Markdown package runs a Vite smoke check. These are component and build checks; they do not prove real authentication or database integration. The repository runner's frontend group currently covers Developer, Maintainer and shared Markdown, **not Reporter**; run Reporter separately.

### Backend, security and repository checks

From the repository root:

```sh
python3 workflow/verify.py
```

The default runs the defined frontend, backend, Reporter backend, security and tooling groups. Use `--group frontend`, `--group backend`, `--group reporter`, `--group security`, or `--group tooling` for a focused run; `--frontend developer`, `maintainer` or `shared/markdown` narrows the frontend group. See `python3 workflow/verify.py --help` and [verification instructions](../workflow/README.md). The Reporter group requires Docker, `python` on PATH and `POSTGRES_JDBC_JAR`; see its README. Security and tooling checks may need network access to download pinned tools; a missing tool, network failure or finding is a failure, not a pass. See [security checks](../workflow/security/README.md).

For the auth/backend suite alone:

```sh
python3 src/backend/auth/test/run_setup.py
```

This creates and removes its own temporary PostgreSQL cluster. It needs `initdb`, `pg_ctl` and `psql` on PATH or in `MP4_POSTGRES_BIN`, Java and the Gradle wrapper's dependencies. A focused run can add `--tests CLASS_OR_METHOD`; it still runs the setup control. Checklist persistence and Reporter integration have additional disposable-database instructions in their respective backend READMEs. CI has separate image and shared-storage checks; the local verification entry point does not establish every hosted or deployed check.

### Browser smoke test

Using a running integrated application, disposable database and separate synthetic accounts, follow the [complete workflow](UserGuide.md#try-the-complete-workflow): sign in as each role, create and assign a report, start investigation, save/share a checklist, submit a resolution and review it. Also try each role in a wrong-role workspace and confirm access is denied. Record the actual revision, results and sanitized evidence. A local browser login test reported for another worktree does not establish behavior for this checkout or a deployed service. Never capture real credentials or sensitive data in screenshots or traces.

## 7. AI integration

Live SoC access is disabled unless configured. `.env.example` lists `SOC_LLM_ENABLED`, `SOC_LLM_MODEL`, role-specific keys, `SOC_LLM_RPM` and `SOC_LLM_TPM`, plus Reporter, Developer and Maintainer model overrides. A role without a configured key is unavailable. Check model access, provider connectivity and approved budgets before enabling it. A key or a passing fake-provider test does not prove live availability or quota compliance.

The gateway uses bounded requests, role routing and PostgreSQL admission controls. Its token setting is an application reservation budget, not a measurement of provider billing. The Developer suggestion flow sends the report and saved checklist, excluding unsaved edits and private notes; the Maintainer summary uses bounded selected queue context. Treat prompts and generated content as untrusted; keep credentials and unnecessary private data out of inputs and evidence. Test authorization, stale results, malformed output, limits and provider failures when changing AI behavior. See the [gateway README](../src/backend/socllm/README.md), [Developer AI contract](../workflow/developer/ai-checklist-contract.md), and [Maintainer README](../src/backend/maintainer/README.md).

### Reporter AI design and request flow

The Reporter AI asks clarification questions about a new draft. It does not
rewrite, submit or persist the draft. Earlier restructuring trials lost content
on long reports; the question-only design leaves all edits with the Reporter.

```mermaid
sequenceDiagram
    actor R as Reporter
    participant UI as NewReport
    participant API as ReportClarifyController
    participant S as ReportClarifyService / Rules
    participant G as SoC role gateway
    participant L as SoC LLM
    R->>UI: Check my report
    UI->>API: POST title and description + CSRF
    API->>API: Check active Reporter and fresh session
    API->>S: Validate draft
    S->>G: REPORTER_ASSIST
    G->>G: Apply policy and PostgreSQL admission
    G->>L: System instructions + untrusted draft
    L-->>S: JSON through gateway
    S->>S: Validate quotes, topics and questions
    S-->>API: Validated result or safe error
    API->>API: Recheck session/account for success
    API-->>UI: Result or error
    R->>UI: Edit draft manually
```

`POST /api/reporter/report-clarify` requires an authenticated, active Reporter,
a fresh session and valid CSRF token. The body must be JSON, at most 128 KiB,
with exactly `{title, description}` and no query string. Report-creation text
rules apply. Refusals happen before the model call; a successful result is
discarded if the session or account changed while it ran.

The trusted system message instructs the model to ignore instructions in the
untrusted title and description, avoid quoting template scaffolding, and never
request screenshots. The model must return exactly:

```json
{
  "unclear": [{"quote": "the second one", "question": "Which action do you mean?"}],
  "missing": [{"category": "Environment", "question": "Which browser and version did you use?"}]
}
```

There are at most 8 unclear items, with unique 1–300-unit quotes copied exactly
from the submitted title or description. At most 7 topic items use each of
Summary, Steps to reproduce, Expected behavior, Actual behavior, Environment,
Frequency, and Error messages and logs once. Questions are 1–300 UTF-16 units on
one visible line. Empty arrays are valid; extra keys, duplicates, wrong types,
inexact quotes or invalid text reject the result.

`ReportClarifyRules` normalizes question text before screening URLs, email and
credential requests. A screened question drops only its item; if all items are
dropped, the response is invalid. Controls, hidden or blank-rendering characters
and questions without a letter or digit reject the whole result. Screening is a
blocklist backstop: lookalike letters and some separator, URL and email variants
remain known limitations. Plain-text rendering prevents generated links or HTML
from becoming active.

The frontend clears results on dismiss, another check, valid submission or
unmount. It cancels its earlier request when appropriate and ignores stale
responses; browser cancellation does not prove a provider call stopped. Results
describe the draft sent at click time and are not refreshed by subsequent edits.
Telemetry records outcomes, model, duration and item counts, without draft text,
quotes or questions. Gateway token usage is unavailable through this endpoint.

### Reporter AI configuration and limits

Load settings into the backend process environment; `.env` is not loaded
automatically. The example keeps AI disabled and contains no secrets.

| Setting | Requirement |
| --- | --- |
| `SOC_LLM_ENABLED=true` | Enables configured live AI services. |
| `SOC_LLM_MODEL` | Nonblank shared model; still required with the Reporter override. |
| `SOC_LLM_MODEL_REPORTER=x-test-2` | Checked-in example choice. Missing or blank falls back to the shared model. |
| `SOC_LLM_API_KEY_REPORTER` | Team-approved Reporter key; missing key leaves checking unavailable. |
| `SOC_LLM_RPM` | Positive request budget approved for the configured key. |
| `SOC_LLM_TPM` | At least 77,824 for Reporter admission. This is an application reservation budget. |

| Policy | Input bytes | Instruction bytes | Output tokens | Deadline | Starts/account | In flight/account | Reservation |
| --- | ---: | ---: | ---: | ---: | --- | ---: | ---: |
| `REPORTER_ASSIST` | 61,440 | 8,192 | 8,192 | 60 s | 3 per 5 min | 1 | 77,824 |
| Developer / Maintainer initial policy | 24,576 | 8,192 | 2,048 | 30 s | 3 per 5 min | 1 | 34,816 |

Transport caps are 384 KiB per provider request and 64 KiB per response. The
provider request has exactly `model`, `messages`, `max_tokens`, `stream` and
`response_format`; no `reasoning_effort` is sent. The Reporter budget provides
headroom for default reasoning. Physical keys shared across roles also share
admission budgets. The Reporter's 2-worker, queue-8 pool can become busy during
long calls. The `x-test-2` provider test slot can change, so recheck compatibility
before live use. Missing AI configuration does not prevent ordinary reporting.

### Reporter errors and verification

| Status | Code / meaning |
| --- | --- |
| 400 | `VALIDATION_FAILED`: body, content type, query or text rules. |
| 401 / 403 | Session/account refusal, wrong role or CSRF refusal. |
| 409 | `AI_BUSY`. |
| 429 | `AI_RATE_LIMITED`; observe `Retry-After`. |
| 502 | `AI_INVALID_OUTPUT`. |
| 503 | `AI_UNAVAILABLE` or `STORAGE_UNAVAILABLE`. |
| 504 | `AI_TIMEOUT`. |

See the [Reporter AI contract](../workflow/reporter/report-clarify-api-contract.md)
for exact shapes and error rules. Requirements are in
`openspec/changes/reporter-report-clarify`; the endpoint contract remains marked
draft for owner review.

Run `npm run verify` from `src/frontend/reporter` for type checks, component
tests and production build. From `src/backend/auth`, run the focused Windows
command below; use `./gradlew` on macOS/Linux:

```powershell
.\gradlew.bat --no-daemon test --tests 'mp4.platform.ReportClarify*' --tests mp4.platform.SocLlmRoleConfigurationTest --tests mp4.socllm.SocLlmHttpProviderTest
```

Real-session integration and admission checks need the disposable PostgreSQL
setup described in the auth README. Do not count skipped database tests as a
pass. Run the Reporter API harness separately with
`python workflow/verify.py --group reporter`, Docker and `POSTGRES_JDBC_JAR`.

For a manual smoke check, sign in as a synthetic Reporter, check a fictional
vague draft, verify exact quotes and useful questions, edit and dismiss without
losing text, then submit and confirm the report. Also exercise unavailable AI
and rate-limit messages. Use another role to confirm refusal before a model
call. Record revision, setup, latency and outcomes separately for fake-provider,
real-session and live-provider checks.

Historical [integration evidence](../logs/reporter-report-clarify/group4.md)
records 23 frontend tests, a 337-test backend run before the later visible-text
fix, and 10 live synthetic drafts returning successfully in 9.3–34.6 seconds.
The later review found a visible-character defect; current code adds a guard
and regression tests, but that earlier review does not approve subsequent code.
The [model-setting check](../logs/reporter-report-clarify/2026-11-13-model-example.md)
records 23 passing configuration/loopback tests and no live request. These are
historical observations, not checks rerun for this documentation edit or proof
of a hosted deployment.

### Maintainer summary evaluation

The Maintainer model can be selected with `SOC_LLM_MODEL_MAINTAINER`; an absent or blank override falls back to the required shared model. The local repair preserved the response contract, deadline and shared limits while revising context selection. Three final synthetic Qwen cases returned valid summaries and recognized facts in descriptions; earlier trials included timeouts, format failures and incorrect missing-information claims. These were small diagnostic trials rather than a completed model comparison or proof of live quotas. See [summary repair evidence](../logs/maintainer/2026-11-12-summary-live-repair.md) and [release checks](../logs/maintainer/2026-11-12-release-checks.md).

## 8. Changes, OpenSpec and evidence

1. Check the branch and uncommitted work. Identify the matching OpenSpec change and task; read its spec, design, tasks and relevant contracts. For Developer checklist work also read the [storage contract](../workflow/developer/checklist-storage-contract.md). Resolve disagreements between spec, contract and tests with the feature owner.
2. Follow the [OpenSpec workflow](../workflow/openspec.md) and choose a proportionate path using [workflow selection](../workflow/workflow-selection.md). For low-risk work, the lightweight path permits one agent and focused checks. For the full path, prepare the contract, fixtures and runner, then follow the independent Red and candidate verification gates in the [TDD workflow](../workflow/tdd.md); authorization and AI security changes also need a separate security reviewer. Follow `AGENTS.md` for the applicable gates and checks.
3. Run the checks required for the affected task and record the result before marking it complete. Validate OpenSpec with `openspec validate --all --strict --no-interactive` when the pinned CLI is installed.
4. Keep evidence proportionate but specific: summarize the goal, changes, checks, review findings and limitations first; then record revisions, context supplied, coordination, commands, results and available resource measurements. Mark unavailable measurements as unavailable. Do not invent token use, savings, test results or deployment evidence. See [TDD evidence format](../workflow/tdd.md#evidence) and [evaluation guidance](../workflow/evaluation/context-resource-evidence.md).

Use targeted context and focused checks appropriate to the change, then run the required broader checks. Preserve other contributors' work and avoid logging secrets or report contents unnecessarily.

## 9. Deployment and release

The repository contains separate development and production Render blueprints and GitHub deployment workflows. The container build includes all three clients and exposes `/assets/revision.txt` for its build revision. Review [deployment instructions](../src/backend/platform/deployment/README.md), workflow files and the actual environment configuration before release; documentation of a blueprint is not evidence that it is deployed.

Use separate databases, keys, rate-limit secrets, public origins and environment-scoped deployment hooks for development and production. Use HTTPS and configure the exact public origin. The workflows gate deployment on the relevant `main` revision and CI; production also checks reviewer configuration. Verify actual environment protections, secret scopes and current CI results before dispatching. Creating cloud resources or deploying requires the appropriate project authorization.

Before release, review migration compatibility and backups, run the applicable checks, then verify the deployed revision, login, role access, report flow and AI availability using approved synthetic data. For rollback, review schema compatibility and select a previously verified deployment; preserve data and migration history. Live hosting, release permissions and rollback were not verified while writing this guide.

## 10. Troubleshooting and known limits

| Problem | Check |
| --- | --- |
| Backend fails at startup | Database URL, credentials, rate-limit secret, database availability and migration compatibility. Do not print secrets or reset history. |
| Local login or CSRF fails | Correct account and role, loopback local-cookie setting, browser origin and `MP4_AUTH_PUBLIC_ORIGIN`; use the same host and port consistently. |
| A report is missing | Reporter ownership, Developer assignment, filters and current session. |
| AI unavailable or rate limited | Enable flag, selected model, role key, budget and provider access; manual workflows remain available. |
| Test runner cannot start | Required tools, dependencies, network access and disposable PostgreSQL prerequisites; distinguish setup failure from an application failure. |
| Existing database rejected | Stop and review the actual schema and history with owners; do not force a repair. |

This guide is a draft. The commands were checked against repository scripts and documentation, but the full suite, live AI, integrated browser flow and deployment were not rerun for this document. Refresh the guide after integration or a release.

## 11. Acknowledgements

The application uses the frameworks and libraries declared in its Gradle and npm manifests, including Spring, PostgreSQL, Flyway, React and Vite. Maintainer documentation consulted earlier MP2/MP3 framework and deployment documentation; the contribution states that no prior application implementation was copied. OpenSpec and Codex specialist agents supported development and review. The repository's [security guidance](../workflow/security/README.md) identifies its scanning tools and their upstream documentation. Review dependency licenses and the project reuse inventory before submission; this guide does not claim a complete attribution audit.
