# Development packaging and deployment checks

Prepared a container that packages the Java application and Maintainer frontend,
a development-only Render Blueprint, and a manual GitHub deployment workflow.
Added a CI job to start the image with disposable PostgreSQL and check health,
frontend availability and the exact source revision. Existing CI jobs are unchanged.

Validated workflow syntax, shell syntax and the Blueprint schema. Independent
candidate and security reviews found no actionable static configuration defects.
Verified the Docker build and packaged startup remotely in CI run 42512619045
on source candidate 61cb733; health, frontend and build revision checks passed.
Have not built or run the image locally: Docker Desktop's Linux engine is unavailable. Have not created Render resources or deployed; no development deploy
hook is configured. Live revision, connectivity, role smoke checks, environment
protections, production separation and rollback checks remain outstanding.
OpenSpec tasks 0.3 and 6.2 remain unchecked.

## Technical evidence

- Configuration candidate: `fcdc395` (same deployment content reviewed as an
  uncommitted delta from `b776d5cdadad2990ff6b679ec3063fe5badd8834`).
- Scope: `.dockerignore`, `render.yaml`, deployment workflow, added image CI job,
  and `src/backend/platform/deployment/`.
- Author: `development_deployment_setup`, implementer role, gpt-6-luna/high.
- Independent candidate review: `deployment_candidate_review`, gpt-6-luna/low,
  read-only; PASS for static criteria, image/live execution unavailable.
- Independent security review: `deployment_security_review`, gpt-6.1-sol/medium,
  read-only; PASS for scoped static trust boundaries. Did not independently rerun
  the schema/shell tools; those remain candidate-review evidence.
- Root check: actionlint 1.7.12 with `-shellcheck= -pyflakes=` on both workflow
  files; exit 0. Shellcheck and pyflakes were disabled, not claimed as passing.
- Candidate check: Git Bash `bash -n` for startup and workflow shell blocks;
  exit 0. Author checked LF-only startup bytes.
- Author check: current official Render Blueprint JSON Schema, Draft 2020-12;
  zero validation errors. Quoted `'off'` to avoid YAML boolean interpretation.
- Docker probe: `docker version --format '{{.Server.Version}}'`; unavailable
  engine pipe. Starting Docker Desktop did not make the engine available.
- Initial packaging attempt could not start Gradle because JAVA_HOME referred
  to an unavailable temporary JDK. Deferred corrected-JDK execution while the
  auth implementer owned the shared build directory; no concurrent Gradle runs.
- Configuration has no production resources. Render project protection is not
  a GitHub approval gate; actual account settings have not been verified.
- No secret values, passwords, deployment hook URLs or private preferences are
  included in this record. Token and resource usage measurements unavailable.

## References checked for configuration

- https://render.com/docs/blueprint-spec
- https://render.com/docs/deploy-hooks
- https://render.com/docs/deploys
- https://render.com/docs/free

Native packaging subsequently passed on `61cb733`: Gradle `authDistribution`
with the external deployment init script, built frontend input and revision
argument; BUILD SUCCESSFUL in 12 seconds. Remote Docker execution subsequently passed in CI run 42512619045.
