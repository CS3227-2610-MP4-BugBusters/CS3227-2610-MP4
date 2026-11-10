# Maintainer workflow service contract

I define the Java boundary for Maintainer report queries and workflow actions in
`mp4.maintainer.workflow.MaintainerContracts` and `MaintainerService`. The
contracts reuse the shared report aggregate and trusted actor; they do not create
a Maintainer-owned report store. This is an executable setup boundary, not a claim
that report behavior, authorization, persistence or an HTTP endpoint is complete.

## Public data and service surface

The canonical domain enums are `MaintainerContracts.Role`, `Status` and
`Priority`. Status values have explicit wire labels (`Submitted`, `Assigned`,
`In progress`, `Resolution proposed`, `Closed`) and an explicit mapping from the
shared database labels (`SUBMITTED`, `ASSIGNED`, `IN_PROGRESS`,
`RESOLUTION_PROPOSED`, `CLOSED`). Adapters must not serialize Java enum names as
wire labels by accident.

`ProposalStatus` deliberately uses the existing proposal-store values
`PENDING`, `SUPERSEDED` and `CLOSED`. `PENDING` is the current reviewable
proposal; returning or invalidating it records `SUPERSEDED`, while successful
closure records `CLOSED`. The API may project a pending proposal as
`currentProposal`; it must not invent a persisted `CURRENT` label.

`Actor` is a trusted session projection containing account ID, role, active state
and generation. It is not request data. `Person` is the public `{id,displayName}`
shape. `AccountEligibility` is an internal-only row used when an adapter rereads
actor/target role, active state and generation while holding account locks.

`ReportCore` carries the shared report identity, source, ownership, lifecycle,
priority, version, timestamps and current proposal reference. `ReportSummary`
matches the public list summary shape. `ReportDetail` is a service projection
composed with a summary and shared-only evidence: notes, published checklist,
responses, comments, current/retained proposals and history. The HTTP mapper must
flatten its summary fields into the existing TypeScript `ReportDetail` shape. It
must not serialize the internal `report` wrapper or add draft/private fields.
There is no field for Developer private notes, draft checklist steps or draft
revision in `ReportDetail` or `AggregateChildren`.

Mutation inputs mirror the proposed request fields: expected report version and
priority; expected version, Developer ID and optional assignment reason; expected
version, proposal ID/version and reason for return; expected version,
proposal ID/version and explicit `reviewed` for close; expected version and
reason for reopen; and expected version and text for comments. Input constructors
check only basic non-null/nonnegative shape. The behavior task must still validate
authorization, lifecycle, text policy and exact state/version rules.

All report, proposal and expected-version values exposed through JavaScript JSON
are constrained to the nonnegative safe-integer range, at most
`9007199254740991`, matching the frontend parser and checklist revision contract.
Session generation remains an internal nonnegative counter.

`MaintainerService` exposes list, detail, active Developer list, priority update,
assignment, resolution return, close, reopen and comment operations. Every method
currently raises `ServiceException(code=NOT_IMPLEMENTED,statusCode=503)` before
consulting a repository port. The setup test verifies the service shape, DTO
construction and that construction does not consult the repository. A separately
approved Red gate is required before implementing any operation.

## Repository and transaction adapter rules

`Repository` supplies authorized safe read projections and one transaction entry
point. Every repository query rechecks the current active MAINTAINER role and
session generation before exposing data; detail authorization and projection use
the same consistent read. `TransactionWork` runs within one adapter-managed transaction: normal
completion commits; an exception rolls back report, child, proposal and history
changes together. The transaction interface encodes the lock sequence:

1. `lockAccountsInIdOrder(expectedActor, accountIds)` locks and rereads the
   current actor and any target account in stable UUID order. The adapter verifies
   the current active Maintainer role and actor generation against the trusted
   input, and returns fresh internal eligibility rows. Assignment additionally
   requires a current active Developer target.
2. `lockReport(reportId)` locks the shared `reports` row and reads the aggregate
   version and lifecycle state.
3. `loadChildren(reportId)` reads checklist, proposal, notes, responses, comments
   and history only after the report lock.
4. The caller applies state/version decisions and uses `updateReport`,
   `updateProposal`, `insertComment` and `appendHistory` in the same unit of work.
   `person(accountId)` resolves display-only author metadata without taking an
   account lock, and `projectSummary(report)` builds a fresh response summary
   using transaction-local metadata. These avoid opening a second repository
   connection after a write to assemble response/history display names.

Adapters must preserve the Developer checklist contract: shared reports row first,
then checklist/proposal child records; private Save changes only its draft
revision; unchanged Share is a no-op; changed Share and closure serialize on the
report lock. Do not hold that transaction across provider calls. Query adapters
must return consistent pagination and safe projections, never draft or private
Developer fields.

## Existing storage and adapter work still required

The shared `reports` migration currently has ID, title, description, reporter,
assignee, uppercase status, aggregate version and current proposal ID. It has no
priority, `created_at`/`updated_at`, reproduction steps, expected/actual behavior
or environment columns. These are additive shared-schema needs, owned by the
shared database owner; this contract does not introduce a second report table.

The current proposal fixture shape has only ID, report ID, status, version and
contents. A real safe proposal projection also needs author identity and creation
time, plus retained proposal history/supersession semantics. The existing
checklist table stores private draft steps/revision and published shared steps;
the Maintainer adapter must read only the published steps and map them to
`ChecklistItem` values after the report lock.

Shared investigation-note, response, comment and history storage/ownership and
their public display-name joins still need registration with the owning shared
services. The account adapter must map the existing trusted session/current-user
contract to `Actor`, and use the shared account lifecycle source for fresh
eligibility. The current Maintainer package has no JDBC adapter, controller,
account adapter or schema integration. Those are not supplied by these setup
files.

## Maintainer discussion decision

Allow Maintainer plain-text discussion on Closed reports, following the plan's all-report discussion permission. Comments increment the aggregate version and append history without changing status or superseding the current proposal. Keep Reporter source edits and Developer publication subject to their unresolved-only rules. Selected this policy for the Maintainer-owned implementation; have not claimed separate teammate acceptance.
