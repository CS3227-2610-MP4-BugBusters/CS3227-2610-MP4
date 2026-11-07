# Spec Delta

## Purpose

Let the assigned Developer record investigation steps and explicitly share them with the Reporter who filed the report. Give authorized Maintainers access to the shared copy for review.

## ADDED Requirements

### Requirement: CL-01 Assigned Developer can save and share steps

The system SHALL allow the currently assigned Developer to read and save checklist steps for their assigned report and explicitly publish the saved steps. The server SHALL reject a Share request if the saved draft contains no steps.

#### Scenario: Save steps for later

- **GIVEN** a Developer is currently assigned to a report
- **WHEN** they save checklist steps and reopen that report
- **THEN** the saved steps appear in their checklist editor

#### Scenario: Share saved steps

- **GIVEN** the currently assigned Developer has saved steps for the report
- **WHEN** they share the saved steps
- **THEN** those steps become available to the report's Reporter

#### Scenario: Direct API Share is rejected for an empty draft

- **GIVEN** an assigned Developer has privately saved an empty draft on a report with a shared checklist and a pending resolution
- **WHEN** the Developer attempts to Share through the API
- **THEN** the server rejects the request and leaves the shared checklist, report state, report version, and pending resolution unchanged

### Requirement: CL-02 Reporter can read shared steps on their own report

The system SHALL give a Reporter read-only access to the most recently shared checklist steps on reports they filed. Saving alone SHALL leave the Reporter’s shared copy unchanged.

#### Scenario: Read steps after sharing

- **GIVEN** the assigned Developer has shared checklist steps on a report
- **WHEN** the Reporter who filed it opens the report or requests its checklist through the API
- **THEN** the system returns the shared steps in read-only form

#### Scenario: Reporter sees only the last shared copy

- **GIVEN** an assigned Developer has saved draft A on a report that has never had shared steps
- **WHEN** the owning Reporter reads the checklist
- **THEN** the Reporter sees no steps
- **WHEN** the Developer shares A and later saves draft B
- **THEN** the Reporter still sees A
- **WHEN** the Developer shares B
- **THEN** the Reporter sees B

#### Scenario: Reporter retains shared steps after a rejected empty Share

- **GIVEN** the Reporter can see shared steps and the Developer has saved an empty draft
- **WHEN** the server rejects the Developer’s attempt to Share the empty draft
- **THEN** the Reporter still sees the last shared steps

#### Scenario: Reporter requests another Reporter's checklist

- **WHEN** a Reporter requests shared steps for a report filed by another Reporter
- **THEN** the system denies the request without disclosing checklist content

#### Scenario: Reporter attempts to change checklist steps

- **WHEN** a Reporter attempts to save or share checklist steps through the API
- **THEN** the system denies the request and leaves the checklist unchanged

### Requirement: CL-03 Unassigned Developer cannot read or edit checklist steps

The server SHALL deny checklist reads, saves, and sharing by any Developer who is not currently assigned to the report. The restriction SHALL apply to direct API requests.

#### Scenario: Direct API requests by an unassigned Developer

- **GIVEN** a Developer knows the ID of a report they are not assigned to
- **WHEN** they send a direct request to read, save, or share its checklist
- **THEN** the server denies the request without returning checklist content or changing checklist state

#### Scenario: Former assignee uses a stale report view

- **GIVEN** D1 opened a checklist with a persisted draft and a distinct shared copy, and the report was then reassigned to D2
- **WHEN** D1 sends direct read, save, and share requests from the stale view
- **THEN** the server denies each request, returns no checklist content, and leaves both stored copies unchanged
- **WHEN** D2 requests the checklist
- **THEN** D2 can read the persisted draft

### Requirement: CL-04 Maintainer can read only the shared checklist

For a report the Maintainer is authorized to view, the system SHALL provide read-only access to the most recently shared checklist. Maintainer responses SHALL omit draft steps.

#### Scenario: Maintainer opens a report before its first share

- **GIVEN** a report has a saved draft and no shared copy
- **WHEN** an authorized Maintainer reads the report in the UI or through the API
- **THEN** the system returns no checklist steps

#### Scenario: Maintainer sees the last shared copy

- **GIVEN** the Developer has shared A and later saved B
- **WHEN** an authorized Maintainer reads the report in the UI or through the API
- **THEN** the checklist contains A and the response contains no draft steps from B
- **WHEN** the Developer shares B and the Maintainer reads the report again
- **THEN** the checklist contains B

#### Scenario: Maintainer retains shared steps after a rejected empty Share

- **GIVEN** the server has rejected an attempt to Share an empty draft on a report with shared steps
- **WHEN** an authorized Maintainer reads the report
- **THEN** the Maintainer still sees the last shared steps

### Requirement: CL-05 Checklist changes follow the report lifecycle

The system SHALL allow the assigned Developer to Save and Share in Assigned, In progress, and Resolution proposed, and deny both actions in Submitted and Closed. Private Save SHALL leave the proposal and report version unchanged. A Share that changes the shared checklist during Resolution proposed SHALL atomically publish the saved steps, increment the report version, supersede the proposal, and return the report to In progress.

#### Scenario: Save privately during resolution review

- **GIVEN** a report is Resolution proposed and the Maintainer can see shared steps A
- **WHEN** the assigned Developer saves B privately
- **THEN** the draft contains B, the Maintainer still sees A, and the proposal and report version remain unchanged

#### Scenario: Share unchanged steps during resolution review

- **GIVEN** a report is Resolution proposed and its saved draft matches its shared steps in order and content
- **WHEN** the assigned Developer shares the draft
- **THEN** the shared steps, report state, report version, and proposal remain unchanged

#### Scenario: Save and Share before a resolution is proposed

- **GIVEN** a report is Assigned or In progress
- **WHEN** the assigned Developer saves and shares changed steps
- **THEN** the system publishes the saved steps and increments the report version on Share while keeping the current report state

#### Scenario: Save and Share denied for Submitted or Closed reports

- **GIVEN** a report is Submitted or Closed
- **WHEN** a Developer attempts to save or share through the API
- **THEN** the server denies the request and leaves the checklist and report unchanged

#### Scenario: Changed Share commits before closure

- **GIVEN** a Maintainer is reviewing a pending proposal and the assigned Developer has saved steps that differ from the shared steps
- **WHEN** Share commits before the Maintainer's closure request
- **THEN** the new steps are visible, the old proposal is retained as superseded, and closure against the previous report version fails with the report In progress

#### Scenario: Closure commits before changed Share

- **GIVEN** a Maintainer is reviewing a pending proposal and the assigned Developer has saved steps that differ from the shared steps
- **WHEN** closure commits before the Developer's Share request
- **THEN** Share fails, the report stays Closed, and the previous shared steps remain visible
