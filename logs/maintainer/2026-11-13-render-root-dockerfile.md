# Render dashboard Dockerfile

Added a repository-root Dockerfile for Render's default `./Dockerfile` setting.
It builds all three frontends and the Java application using the existing
deployment stages. Kept the existing nested Dockerfile for CI/Blueprint callers;
its build stages match the root file. Both normalize the startup shell script's
line endings so a Windows checkout can run the default Linux entrypoint.
Documented the Render dashboard fields and reuse of an existing Supabase database.

The image built successfully and launched through its default entrypoint against
a fresh disposable PostgreSQL database. Verified a nondefault Render-style PORT,
health, role pages, Account resources and the packaged base revision. Deleted only
the isolated smoke containers/network afterward. Existing local app/database and
remote services were not changed. Render/Supabase connectivity and hosted login
remain unverified; no deployment was requested or performed.

## Implementation and coordination

- Starting revision: `62471fa3a6d850b1fe7e277f33cbd253c412993a`, clean `main`.
  Tested this revision plus the uncommitted Dockerfile and documentation changes.
- Related OpenSpec: complete-maintainer-role tasks 0.3/6.2, deployment preparation
  only. Read proposal, relevant design/tasks and MNT-INTEGRATE-01/MT-26. Neither
  broad task is marked complete by this local packaging check.
- Lightweight packaging path; the owner's preceding instruction was to avoid TDD.
  One coordinator implemented and reviewed the diff. No delegated or independent
  review, Red baseline or full release verification is claimed.
- Context retrieved: existing Dockerfile/start script, deployment README,
  render.yaml, ignore rules and official Render Docker documentation.

## Findings and fixes

- Render's default Dockerfile path did not match the existing nested location.
  Added the root entry and documented leaving Root Directory blank to preserve
  the repository-wide build context. Root and nested stage equality passed.
- The checked-out start.sh contained 76 CRLF line endings. Normalize inside both
  images before chmod; no Java-entrypoint override is needed in the smoke check.
- No database schema, application behavior, credentials or environment protection
  settings were modified. Existing Supabase environment values stay outside images.

## Verification

- `docker build --build-arg GITHUB_SHA=<base revision> -f Dockerfile -t mp4-render-check .`:
  PASS. Existing frontend stages were cached; Java packaging completed successfully
  in approximately 34 seconds as reported by Gradle.
- Image manifest list:
  `sha256:0817989a78a65602991e9fdc5e6fca3773904befdb843476c56bf93cfb496863`.
- Root and nested Dockerfile stage comparison, excluding the root explanatory
  comment: PASS. `git diff --check`: PASS.
- Isolated Docker smoke, completed by 12:50:37 Asia/Singapore on 13 November 2026:
  default `/app/start.sh`, generated in-memory test secrets, fresh PostgreSQL 17,
  `PORT=10001` mapped to loopback 18084. Health returned 200; root, Developer,
  Reporter, Account and shared Account stylesheet returned 200; revision asset
  equalled the base revision. Fresh migrations ran as part of startup. Cleanup
  removed only the timestamped test app/database containers and test network.
- No application code changed, so frontend suites were not rerun for this task.
  This smoke is local packaging/fresh-schema evidence, not existing Supabase
  upgrade, Render connectivity, live authentication or production verification.

## Reflection and resources

Reused existing build stages and tested the real default entrypoint rather than
the earlier local Java override. The two Dockerfiles must remain synchronized;
the deployment README records that maintenance requirement. Actual model/effort,
input/cached/output/reasoning token counts, cost and sanitized trace locations are
unavailable. No resource savings or independent review are inferred. Build output
provides only the Gradle timing above; no complete end-to-end elapsed measurement
was captured.

Reference: [Docker on Render](https://render.com/docs/docker), checked this run.
