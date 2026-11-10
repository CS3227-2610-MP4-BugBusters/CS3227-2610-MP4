# MP4 gateway security review and repair tracking

Independent security review required changes before accepting the port. The
candidate could join a caller-owned SQL transaction, allowing quota metadata to be
rolled back after provider work. It also considered shared-key budget limits only
inside each replica, allowing a less restrictive replica to weaken the quota.
Both findings were repaired with real PostgreSQL regression tests. A later key
normalization finding was also fixed. Final independent source recheck passed,
and the independent runtime verifier passed all 47 selected tests on the final
21-file snapshot. Historical findings and both repair rounds are retained below;
see [final verification](verification.md) for commands, hashes and live-check limits.

## Initial review

- Task: SOC-MP4-PORT; initial candidate manifest `candidate-initial-manifest.json`, starting HEAD
  `6bdba80519ed30996cf401f6b28dc60fb31f3e75`.
- Reviewer: fresh `/root/socllm_security`, `security_reviewer`, reported
  `gpt-6.1-sol`, reasoning effort `medium`.
- All 20 initial candidate file hashes matched. Reviewer changed no files, ran
  no Gradle command, and made no live requests. Findings came from static analysis.
- P1: default REQUIRED transaction propagation in `PostgresSocLlmAdmission`
  joins an ambient transaction. Acquisition can return before durable commit;
  worker renewal then accepts an UPDATE of zero rows. Provider work can proceed
  while caller rollback erases accounting and a quota lock spans provider latency.
- Required repair: independent commit, reject provider entry under caller-owned
  transactions, require exactly one renewal row, and test rollback/zero-row behavior.
- P2: stricter request/token limits are computed only from each instance's alias
  map. For the same physical key/store, RPM 1 on one instance and RPM 5 on another
  can permit the second call after the stricter instance's first admission.
- Required repair: shared authoritative policy or mismatched-policy rejection,
  real two-instance divergent RPM/TPM tests, documented policy update semantics.
- Candidate review also identified missing direct PostgreSQL coverage of the
  four-active-request cap per physical key. Added that to the repair scope.
- Existing Maintainer paths preserve trusted actor/session identity, current access
  and full-source rechecks, private-note exclusion, exact output validation and
  absence of model-driven domain writes. Running-worker leases are retained even
  after timestamp expiry; cancellation ownership was reviewed.
- Live provider compatibility, actual token accounting, host connectivity,
  grounding and crash-recovery operations remain outside this controlled review.

## Repair status

Repair round 1 passed 44 tests in eight suites, with no errors, failures or skips
and a captured exit 0 (55 seconds). The independent security reviewer verified all
21 hashes in `candidate-round1-manifest.json` and confirmed both original findings
fixed: ambient transactions are rejected, admission uses REQUIRES_NEW, lease updates
require one row, and PostgreSQL persists monotonic minimum key policies. Tests cover
divergent replicas in both registration orders and the active-key cap despite expired
timestamps.

The recheck identified one further P2: `KeyBudget` hashes raw secret bytes while Java
HTTP headers trim surrounding whitespace. Padded aliases can therefore send the same
credential under different budget fingerprints. The reviewer confirmed Java header
trimming through read-only `javap` inspection of the local JDK. A JShell probe was
blocked by preferences permissions; no live calls or Gradle checks were run by the
reviewer. No additional access, private-note, output-authority, delivery or worker
lifetime bypass was identified.

Repair round 2 is limited to shared credential normalization/validation, affected
configuration, and HTTP/header/PostgreSQL alias regressions. The independent final
source recheck passed against all 21 hashes in `candidate-round2-manifest.json`.
One canonical Bearer-token representation is now used by admission fingerprints,
HTTP headers and direct role-service construction; invalid role keys are omitted
independently. The earlier transaction, exact lease-row and shared-policy fixes
remain intact. No further actionable finding was identified. The reviewer ran no
Gradle command or live request. Runtime checks are recorded separately; source
review does not substitute for them. The approved baseline test remains unchanged.
