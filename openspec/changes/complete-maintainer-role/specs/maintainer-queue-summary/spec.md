# Spec Delta

## Purpose

Help Maintainers understand selected unresolved reports through a bounded advisory
SoC summary that remains grounded, access-controlled and safe under changing inputs.

## ADDED Requirements

### Requirement: MNT-AI-01 Grounded advisory summary

The system SHALL summarize authorized unresolved reports matching the selected
queue, with supporting report IDs, blockers, missing information and uncertainty.
It SHALL show included/excluded reports and field truncation, and SHALL NOT change
assignment, priority, investigation, proposal or status from generated content.
Developer private notes and their revision SHALL NOT enter provider context, summary
responses or reusable summary cache entries.

#### Scenario: S1 Generate a bounded summary

- **WHEN** an active Maintainer requests a nonempty selected unresolved queue
- **THEN** only authorized bounded fields reach SoC and every reference belongs to supplied reports
- **AND** the UI displays capture time, review label and server-computed included/excluded IDs
- **AND** the summary makes no unsupported claim that tests or investigations occurred

#### Scenario: S1 Private notes excluded

- **GIVEN** a selected report has Developer private notes containing a unique fixture marker
- **WHEN** the Maintainer generates or reuses a summary
- **THEN** the provider input, returned summary and reusable cache entry contain neither the private notes nor their revision
- **AND** ordinary authorized summary generation remains available

#### Scenario: S1 Empty queue

- **WHEN** the selected unresolved queue is empty
- **THEN** a deterministic no-data result appears with zero provider calls

### Requirement: MNT-AI-SEC-01 Untrusted input and validated output

The system SHALL separate task instructions from untrusted report text, validate
output shape, sizes and supplied report IDs, and render generated content as plain
text. Generated links or instructions SHALL NOT gain execution or mutation authority.
Invalid output SHALL NOT be presented as a valid summary.

#### Scenario: SEC-02 Invalid report reference

- **WHEN** the provider cites a report absent from the included input set
- **THEN** the output is rejected with a safe error and no domain mutation

#### Scenario: Injection or malformed output

- **WHEN** source text requests rule bypass or provider output has malformed fields or excessive size
- **THEN** source instructions confer no authority and invalid output is rejected
- **AND** structurally valid markup-like text renders harmlessly as plain text
- **AND** live grounding/usefulness is assessed separately from deterministic safety checks

### Requirement: MNT-AI-FRESH Full-queue freshness and delivery access

The system SHALL recheck current authorization, selected queue membership and all
relevant member versions/assignments before generated or cached delivery. Checks
SHALL include context-excluded members and newly eligible reports. Changed inputs
SHALL invalidate delivery as a current summary; lost access SHALL disclose no result.

#### Scenario: MT-18 Changed or excluded source

- **WHEN** a report version, assignment or queue membership changes during generation
- **THEN** the result is rejected as stale without generated content, including excluded-member changes
- **AND** regeneration uses a fresh authorized snapshot

#### Scenario: MT-19 Access lost

- **WHEN** the requesting session or active Maintainer access is revoked before delivery/reuse
- **THEN** no generated or cached protected content is delivered

### Requirement: MNT-AI-RESOURCE Bounded failure and shared quotas

The system SHALL enforce documented input/output, per-user and shared provider
budgets, deadlines and duplicate-request protection. Production SHALL fail closed
when durable quota accounting is unavailable. Errors SHALL preserve queue state
and entered work without leaking raw provider data or disabling non-AI workflows.

#### Scenario: SEC-03 Timeout or unavailable provider

- **WHEN** generation times out, is rate-limited or the provider becomes unavailable
- **THEN** a safe availability/rate error appears and entered work remains
- **AND** the event is not counted as a successful usefulness or prompt-security trial

#### Scenario: MT-20 Worker survives timeout

- **WHEN** a timed-out worker ignores cancellation or replicas contend for the same budget
- **THEN** in-flight protection remains until actual termination and duplicate calls stay bounded
- **AND** unavailable production quota accounting admits no provider call

### Requirement: MNT-AI-REUSE Authorized matching result reuse

The system SHALL reuse only a valid matching result within its retention limit,
after the same fresh authorization and full snapshot checks as generation. Changes
to source, membership, permissions, prompt/context policy or model SHALL invalidate
reuse. Application reuse SHALL be measured separately from provider prompt caching.

#### Scenario: MT-21 Reuse and invalidation

- **WHEN** unchanged authorized inputs repeat within the cache lifetime
- **THEN** a valid matching result can be reused without a provider call
- **WHEN** any relevant source, permission, prompt or model input changes
- **THEN** the old result is not reused and explicit regeneration remains budgeted
