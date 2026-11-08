# Maintainer OpenSpec migration - 8 November 2026

Migrated the complete Maintainer planning set from commit `2130dcf` into OpenSpec
following the request to replace the old workflow specifications and update PR #3.
Used the generated proposal workflow and read the schema instructions before
writing proposal, capability deltas, design and tasks. No application behavior was
implemented and no Red/Green or feature-owner test-baseline approval was claimed.

Kept the implemented report-browser baseline in main specs and completed its DTO,
query, state and QR acceptance details. Added `complete-maintainer-role` for future
triage, resolution/discussion, SoC summary and service-integration requirements.
Migrated proposed HTTP/transaction/resource contracts, MNT-P0 through MNT-P6 tasks,
reflection/release work and all MT-01 through MT-26 verification oracles. All 21
future tasks remain unchecked. Artifact completion is not feature completion.

Applied the already approved checklist contract instead of the old blanket
checklist-edit invalidation rule: private Save uses a draft revision, unchanged
Share is a no-op, changed Share atomically changes the published copy/report version
and supersedes a pending proposal, and Maintainers see shared steps only.
Preserved remaining proposed policies and shared ownership decisions as proposals.

Updated the configured `candidate_verifier` instructions to use current OpenSpec
artifacts and relevant contracts rather than superseded workflow documents or an
old review session. Dispatched a fresh read-only verifier with configured
`gpt-6-luna`, low effort and an eight-minute budget. Supplied all five source
documents, migration artifacts, base revision and checklist contract. The verifier
compared coverage and independently reproduced strict validation: PASS, no lost
requirements or blocking contradictions found. Token/cache metrics were unavailable.

After coverage review, deleted the five old Maintainer workflow documents and
updated the live workflow guide and frontend README reference. Retained the team
TDD runbook, checklist contract, historical logs and checksum manifests unchanged.
Those old manifests identify their historical source revisions, not current paths.
The migration map records each removed document's replacement.

Checks: `openspec validate --all --strict --no-interactive` passed all three items;
`openspec list --json` reports 0/21 completed future Maintainer tasks. Checked local
Markdown targets, requirement/test ID coverage and stale active references after
deletion. Application source/tests, teammate checklist artifacts and agent model
settings are unchanged. No application test rerun or deployment was needed for this
documentation-only migration. Checked this summary against commands and handoffs
before preparing the current PR update; teammate approval is not claimed.

The independent verifier also rechecked the final post-deletion migration map and
active references: PASS, no findings.
