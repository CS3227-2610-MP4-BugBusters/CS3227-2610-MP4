# Proposal

## Why

Developers need a place to save investigation steps and share them with the Reporter who filed the report. Because saved steps may contain work in progress, access must depend on the current assignment and report ownership, including for direct API requests.

## What Changes

- **CL-01** Give the currently assigned Developer a checklist on the report with separate Save and Share actions. Require at least one saved step before sharing.
- **CL-02** Show the Reporter the steps explicitly shared on their own report.
- **CL-03** Deny checklist reads and edits to unassigned Developers at the API, even when they know the report ID.

- **CL-04** Show the Maintainer only the most recently shared checklist for a report they can access.

- **CL-05** Allow private Save during resolution review. A Share that changes the published steps supersedes the pending proposal and returns the report to In progress.

### Out of Scope

AI drafting and assessment.

## Capabilities

### New Capabilities

- `investigation-checklist` covers saving, sharing, and reading manually authored steps under report-specific access rules.

### Modified Capabilities

## Impact

The change needs checklist storage, server-side authorization for checklist access, and editing and viewing controls on the report detail screens.

### Missing Prerequisites

PR #1 includes a demo-backed Maintainer frontend and proposed shared contracts. End-to-end checklist verification still needs the following application foundations.

- An authenticated application and API that identify Developer, Reporter, and Maintainer roles from trusted server-side data.
- A persisted report with a stable ID, the filing Reporter's ID, and the current assigned Developer's ID, along with a way to establish and change assignment.
- An application storage and migration mechanism for checklist data, and report detail screens for the relevant roles.

### Decisions

- Decide whether to add an optional notice that a newer saved draft exists. The notice would disclose no draft steps.
- **CL-05 approved.** Private Save preserves the report version and pending resolution. A Share that changes the published steps during resolution review supersedes the proposal and returns the report to In progress. See the integration wording in the design.

See [Design](design.md#team-decisions-before-implementation) for the cross-role review points.
