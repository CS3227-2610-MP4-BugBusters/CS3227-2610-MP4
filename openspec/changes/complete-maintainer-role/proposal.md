# Proposal

## Why

The read-only Maintainer frontend exists, but the rest of the role is still planned
in separate workflow documents. Consolidate those existing requirements in OpenSpec
so implementation, security checks and remaining integration work share one source
of truth.

## What Changes

- Carry forward MNT-TRIAGE-01/T1, MNT-ASG-01/A1, MNT-RES-01/R1,
  MNT-DISC-01/D1, MNT-SEC-01/X1 and MNT-AI-01/S1/MNT-AI-SEC-01/S2.
- Preserve the proposed HTTP, transaction, context, output and resource contracts;
  keep unconfirmed integration decisions explicitly proposed.
- Preserve MT-01 through MT-26 and the original MNT-P0 through MNT-P6 task mapping.
- Align checklist reads, versioning and closure races with approved CL-04/CL-05
  and the Developer storage contract: private Save does not invalidate a proposal;
  a changed published Share does.
- Remove the superseded `workflow/maintainer/` documents after coverage review.
  Preserve historical logs and their original commit/hash references.

This PR migrates planning only. Future implementation tasks remain unchecked;
it does not add triage, resolution actions, AI, authentication or persistence.

## Capabilities

### New Capabilities

- `maintainer-triage`: priority, assignment and reassignment.
- `maintainer-resolution-review`: discussion, return, close, reopen and proposal validity.
- `maintainer-queue-summary`: advisory SoC summaries with grounded context and resource controls.
- `maintainer-service-integration`: trusted server access, shared contracts and end-to-end release verification.

### Modified Capabilities

None. The existing `maintainer-report-browser` baseline remains the implemented
frontend; only its contract documentation and migration traceability are completed.

## Impact

Future work affects Maintainer React/Java modules and owner-provided auth, report,
lifecycle, discussion, database and AI adapters. The current change affects only
specifications, planning references and migration evidence. Existing synthetic
data is unchanged. Shared services and live SoC checks remain pending.

Own authentication/account provisioning and Render deployment/GitHub Actions/environment
settings as the Maintainer owner, following the active ownership table in the
original plan (section 2, page 2). Deliver authentication through
[add-shared-authentication](../add-shared-authentication/proposal.md) before further
isolated role UI increments. The Reporter owner reviews authentication; the
Developer owner reviews deployment. The Developer owner owns database schema,
migrations/version checks and shared frontend/API handling; review and integrate
those interfaces without taking over their modules. The Reporter owner coordinates
documentation/site/evidence and evaluation; contribute role material and review
rather than claim final guide assembly or experiment leadership from the cancelled
bottom workload split. Exclude repository integration, code execution, file
attachments, email, automatic fixes and previous-MP implementation reuse.
