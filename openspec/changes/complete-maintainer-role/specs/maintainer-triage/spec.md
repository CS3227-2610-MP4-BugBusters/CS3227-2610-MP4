# Spec Delta

## Purpose

Let Maintainers prioritize reports and assign work without bypassing current
account eligibility, assignment access rules or concurrent-write protection.

## ADDED Requirements

### Requirement: MNT-TRIAGE-01 Versioned priority changes

The system SHALL accept priority changes only from an active Maintainer using the
expected report version. A successful change SHALL preserve unrelated report
content and prior history, increment the aggregate version and append a priority event.

#### Scenario: T1 Change priority

- **WHEN** an active Maintainer submits a valid priority and matching version
- **THEN** only the priority, aggregate version, update time and corresponding history change
- **AND** unknown priorities and stale versions are rejected without partial writes

### Requirement: MNT-ASG-01 Assign and reassign active Developers

The system SHALL atomically assign a Submitted report to an active Developer and
move it to Assigned. Reassignment SHALL require Assigned or In progress, a different
active Developer and a reason, then return the report to Assigned while retaining evidence.

#### Scenario: A1 Eligible assignment

- **WHEN** an active Maintainer assigns a Submitted report with a current expected version
- **THEN** assignee, state, version and history commit together
- **AND** missing, inactive or wrong-role targets are rejected unchanged

#### Scenario: A1 Reassignment removes former access

- **GIVEN** Developer D1 is assigned and the report is Assigned or In progress
- **WHEN** a Maintainer reassigns it to D2 with a reason and matching version
- **THEN** the report becomes Assigned to D2 and retains notes, shared checklist and history
- **AND** D1 loses access to subsequent reads, writes and generated-result delivery
- **AND** invalid state, absent reason or stale version leaves all records unchanged

#### Scenario: X1 Assignment races with deactivation or another write

- **WHEN** target/actor deactivation or a conflicting write races with assignment
- **THEN** only an eligible serialized operation commits, with no partial assignment or history
- **AND** stale writes return 409 without replacing the committed state

### Requirement: MNT-TRIAGE-UI Explicit actions and recoverable failures

The frontend SHALL expose explicit priority and assignment actions, disable duplicate
submissions while pending, refresh eligible actions after success and preserve
recoverable user input after validation, transport or version failures.

#### Scenario: Conflict while triaging

- **WHEN** a mutation returns a version conflict
- **THEN** unsaved reasons remain available and the user can reload and reconcile
- **AND** the frontend does not silently overwrite the report or automatically retry the write
