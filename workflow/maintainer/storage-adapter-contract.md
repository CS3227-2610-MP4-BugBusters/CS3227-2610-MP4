# Maintainer storage adapter decisions

Use the existing `reports` row as the only report aggregate. Preserve its current
columns and uppercase status values, the Developer's shared checklist table and
five-column proposal shape. Add compatible storage through a new migration rather
than edit the applied `001_create_reports.sql` or reset existing data.

## Additive records

Selected the existing Maintainer priority vocabulary and MEDIUM default. Extend
`reports` with priority, creation/update timestamps and the remaining source-text
fields needed by the current browser DTO. Empty defaults preserve existing inserts;
new timestamp defaults mark migration/recording time for legacy rows, not a claim
about an earlier unknown creation time. New role operations keep all source fields
unchanged. Do not add account foreign keys before legacy identities are reconciled.

Keep proposal core columns exactly `id, report_id, status, version, contents`, as
used by the Developer's current Share implementation and positional fixture inserts.
Use only PENDING, SUPERSEDED and CLOSED. Keep author/time metadata separately keyed
to proposal ID so those inserts remain compatible; capture the current assignee as
creator when a new proposal is recorded through the assigned-Developer contract.
Retain old proposal rows when returning, closing or reopening. Do not reconstruct
old proposal authors from a later reassignment.

Use one shared history and discussion store per report. Store actor identity,
plain text, event time and aggregate version; append Maintainer changes in the same
transaction as the report/proposal write. Shared investigation evidence and
Reporter responses are distinct from Developer private notes. The Maintainer reads
a safe shared projection and never gains an instruction, source or private-note
write method.

## Shared checklist boundary

Read only `investigation_checklists.shared_steps`. Never select private draft
steps or draft_revision into a Maintainer response. Current storage exposes strings,
while the existing browser requires an ID for each rendered item. A deterministic
read-only view key may identify the displayed shared text; it is not a Reporter
response selector or proof that canonical stable response-step IDs are implemented.
Keep that integration requirement explicit until the owning component supplies it.
Private Save and unchanged Share must preserve this public projection and proposal.

## Migration and transaction boundary

Register the current MP4 report/checklist SQL under one tracked shared migration
history without copying any prior MP application SQL. Verify an existing manual
schema before baselining its known versions; reject incompatible partial shapes.
Apply additive Maintainer storage afterward. Keep the existing separate auth history
out of the shared registry. Startup must not silently skip missing shared tables.

For a Maintainer mutation, lock current actor/target accounts in a consistent UUID
order, then the shared report, then proposal/history/discussion children. The
Developer's report-before-checklist/proposal ordering remains intact; never acquire
an account lock after the report lock. Recheck current actor generation/role/active,
target eligibility, expected version and lifecycle in that transaction. Match the
old report version in the update and append no partial history. Bound database waits.

Advance updated_at when an aggregate version changes, including a shared versioned
write; never automatically increment version for a private draft or private note.
Keep provider calls outside all storage transactions. Map storage failures to safe
errors, and do not substitute preview records into production.

## Verification boundary

These choices guide the new adapter tests and implementation; they are not evidence
of deployed storage or a teammate's approval. Verify fresh and compatible legacy
migration, private projection, real-session CRUD, rollback, competing versions,
account-disable ordering and both Share-versus-close commit orders before marking
integration complete. Recheck incoming owner branches before the final PR.
