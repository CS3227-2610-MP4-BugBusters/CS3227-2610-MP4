## ADDED Requirements

### Requirement: DN-01 Private investigation notes

The system SHALL provide one editable plain-text private notes field per report to its currently assigned active Developer. An authorized read before any save SHALL return empty text at revision zero. Saving or clearing notes SHALL use an expected notes revision and increment that revision without changing the report version.

#### Scenario: Save and reopen notes
- **GIVEN** an active Developer is assigned a report with no notes
- **WHEN** the Developer saves text using revision zero and reopens the notes
- **THEN** the saved text is returned with revision one

#### Scenario: Clear notes without resetting the revision
- **GIVEN** notes have text at revision two
- **WHEN** the assigned Developer clears them using revision two
- **THEN** an authorized read returns empty text at revision three

### Requirement: DN-02 Current assignment and privacy

The server SHALL authorize note reads and changes against the current assignment and active Developer role. Reporter, Maintainer, unrelated Developer and former assignee SHALL receive no note text or revision, including in report-detail responses or direct API requests.

#### Scenario: Reassignment transfers notes access
- **GIVEN** D1 has saved notes and the report is reassigned to D2
- **WHEN** D1 attempts a read or change after reassignment commits
- **THEN** the server denies the request without returning or changing notes
- **WHEN** D2 reads the notes
- **THEN** the saved text and current revision are available to D2

#### Scenario: Other roles cannot access private notes
- **WHEN** the Reporter or Maintainer requests report details or private notes
- **THEN** the response contains neither note text nor notes revision

### Requirement: DN-03 Safe versioned updates

The system SHALL reject a notes change using a stale notes revision without changing stored notes, and SHALL preserve the Developer's unsent input in the UI. Modification SHALL be allowed only in Assigned, In progress or Resolution proposed; a currently assigned Developer MAY read notes in Closed but SHALL NOT modify them.

#### Scenario: Two Developers' stale views
- **GIVEN** two clients opened revision one of the notes
- **WHEN** one saves a change and the other submits revision one
- **THEN** the second change is rejected, the first change remains, and the second client's unsent text remains available

#### Scenario: Closure before a notes change
- **GIVEN** an assigned Developer has an open notes editor and the report is closed
- **WHEN** that Developer attempts to save or clear notes after closure commits
- **THEN** the request is denied and stored notes are unchanged

### Requirement: DN-04 Private notes do not invalidate resolution

A private notes save or clear during Resolution proposed SHALL preserve the report status, report version and pending resolution proposal while updating the notes revision. This decision requires reconciliation with the existing Maintainer lifecycle wording before implementation.

#### Scenario: Edit notes during resolution review
- **GIVEN** a report has a pending resolution proposal and the Developer is currently assigned
- **WHEN** the Developer saves or clears private notes with the current notes revision
- **THEN** the notes revision advances and the proposal, report status and report version remain unchanged

#### Scenario: Former assignee writes after reassignment
- **GIVEN** D1 has an open notes editor and reassignment to D2 commits
- **WHEN** D1 attempts a notes change
- **THEN** the server denies it and the notes, report and proposal remain unchanged
