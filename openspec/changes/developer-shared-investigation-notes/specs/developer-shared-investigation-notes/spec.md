## ADDED Requirements

### Requirement: DSN-01 Separate shared-note draft

The system SHALL provide the currently assigned active Developer a shared investigation-note editor separate from existing private notes. Save SHALL check and increment a draft revision without changing published data, the report version or a pending proposal. Text SHALL be plain text, at most 10,000 UTF-16 code units, without NUL or invalid Unicode. Empty drafts MAY be saved; stale writes SHALL fail without mutation and retain unsent editor text.

#### Scenario: Initial state
- **WHEN** an assigned Developer opens an unsaved draft
- **THEN** it is empty at revision zero

#### Scenario: Save without publication
- **GIVEN** a report has a published note and a different edited draft
- **WHEN** the assigned Developer saves the draft
- **THEN** Maintainers continue to see only the previous published note
- **AND** existing private notes, report version and any pending proposal remain unchanged

### Requirement: DSN-02 Explicit publication

The assigned active Developer MAY share the last saved nonblank draft while the report is Assigned, In progress or Resolution proposed. Share SHALL check the draft revision, report version, authorization and lifecycle. A changed Share SHALL replace the published note, increment report version and record history atomically; identical Share SHALL be a no-op. Unsaved browser text SHALL NOT be published.

#### Scenario: Rejected publication
- **WHEN** sharing is stale, empty or ineligible, including after closure or reassignment
- **THEN** the system rejects it without changing stored data

#### Scenario: Publish and revise
- **GIVEN** a Developer has saved note A
- **WHEN** the Developer shares it and subsequently saves B without sharing
- **THEN** the Maintainer sees A and no draft content
- **WHEN** the Developer shares B
- **THEN** the Maintainer sees B as the current published note

#### Scenario: Share against review
- **GIVEN** a current resolution proposal exists
- **WHEN** a changed shared-note publication commits before closure
- **THEN** the proposal becomes superseded, the report returns to In progress and stale closure is denied

### Requirement: DSN-03 Access and privacy

Only the active currently assigned Developer SHALL read or write the draft or initiate sharing. Maintainers SHALL read the published note in report details but SHALL NOT receive the draft or existing private notes. Reporters and unrelated Developers SHALL NOT receive either draft or published note through the new feature. Requests SHALL use trusted session identity, CSRF protection and no-store; denied requests SHALL NOT disclose text or revisions.

#### Scenario: Reassignment and closure
- **WHEN** the report is reassigned
- **THEN** the new assignee inherits the draft and published note and the former assignee loses access on subsequent requests
- **AND** a Closed report may be read by its assigned Developer but cannot be edited or shared

#### Scenario: Other roles cannot see private material
- **WHEN** a Reporter, former assignee or unrelated Developer requests the draft or shared note
- **THEN** access is denied without exposing text or revision
- **AND** the Maintainer report detail exposes only the published note and unrelated pre-existing shared evidence

### Requirement: DSN-04 Safe UI and evidence

The Developer interface SHALL label private and shared notes separately, preserve unsaved text after a stale or failed write and require explicit Save and Share. Maintainer output SHALL render note text safely. Verification SHALL cover authorization, stale writes, no-op and changed Share, proposal invalidation and closure/reassignment races using authenticated requests and PostgreSQL; record actual workflow and available usage measurements without inferring missing data.

#### Scenario: Publication is deliberate
- **WHEN** the Developer types in the shared-note editor but does not save and share
- **THEN** the Maintainer cannot see those edits
