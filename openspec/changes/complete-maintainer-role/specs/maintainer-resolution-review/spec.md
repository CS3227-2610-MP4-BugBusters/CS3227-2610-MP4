# Spec Delta

## Purpose

Give Maintainers explicit resolution review and discussion actions while retaining
history and preventing closure of superseded or concurrently changed proposals.

## ADDED Requirements

### Requirement: MNT-RES-01 Review return close and reopen

The system SHALL allow only active Maintainers to return, close or reopen reports.
Closure SHALL require Resolution proposed, explicit review and matching current
report/proposal identity and versions. Return and reopen SHALL require reasons
and retain prior proposals and history.

#### Scenario: R1 Review and close a current proposal

- **WHEN** the Maintainer explicitly reviews and closes a still-current proposal
- **THEN** the report becomes Closed with an atomic history event
- **AND** stale, missing, superseded or invalid-state proposals cannot be closed

#### Scenario: R1 Return a resolution

- **WHEN** the Maintainer returns a current Resolution proposed report with a reason
- **THEN** it becomes In progress and the previous proposal is retained as superseded
- **AND** absent reasons or stale versions cause no partial write

#### Scenario: R1 Reopen a report

- **WHEN** the Maintainer reopens a Closed report with a reason and current version
- **THEN** it becomes Submitted with current assignee/proposal cleared and history retained
- **AND** missing reasons or a non-Closed state are rejected unchanged

### Requirement: MNT-RES-02 Atomic proposal invalidation

Authorized detail, response, evidence or investigation-note edits during Resolution
proposed SHALL atomically supersede the proposal and return the report to In progress.
Checklist publication SHALL follow CL-05: private Save and unchanged Share preserve
the proposal/version; changed Share atomically invalidates the proposal.

#### Scenario: MT-11 Invalidating source edit wins

- **WHEN** an authorized source edit or changed checklist Share commits before closure
- **THEN** the earlier proposal is retained as superseded and the report becomes In progress
- **AND** closure of the previous version fails without partially changing evidence or history

#### Scenario: MT-11 Closure wins

- **WHEN** closure commits before an unresolved-only source edit or changed Share
- **THEN** that edit or Share fails, the report stays Closed and prior published evidence remains
- **AND** a Maintainer must reopen before unresolved-only edits resume

#### Scenario: MT-11 Private draft or unchanged Share

- **GIVEN** shared checklist A and a pending proposal
- **WHEN** the Developer privately saves B or shares unchanged A
- **THEN** the Maintainer still sees A and the proposal/report version stay intact
- **AND** a later changed Share must check the draft revision and report version atomically

### Requirement: MNT-DISC-01 Read-only evidence and plain-text discussion

Maintainers SHALL be able to read and add authorized plain-text discussion with
author and time, without gaining ownership of Reporter source fields or Developer
notes/checklist instructions. Discussion SHALL NOT directly change report status
or supersede a proposal.

#### Scenario: D1 Add a valid comment

- **WHEN** an active Maintainer adds valid text through the discussion contract
- **THEN** author/time and text persist and render safely, with lifecycle unchanged
- **AND** blank, oversized or actor-spoofed input is rejected

#### Scenario: CL-04 View shared checklist only

- **WHEN** the Maintainer opens report evidence before or after private checklist saves
- **THEN** the response contains only the last shared steps, or no steps before first Share
- **AND** draft steps and draft revision are omitted, including after rejected empty Share

### Requirement: MNT-RES-UI Preserve input during review failures

The frontend SHALL show current proposal evidence and explicit review actions,
retain typed reasons/comments after recoverable failures and discard late protected
content after access or selection changes. Generated suggestions SHALL NOT approve closure.

#### Scenario: Review becomes stale

- **WHEN** the reviewed proposal is invalidated before a close request commits
- **THEN** the UI reports the conflict and requires refreshed review rather than closing automatically
