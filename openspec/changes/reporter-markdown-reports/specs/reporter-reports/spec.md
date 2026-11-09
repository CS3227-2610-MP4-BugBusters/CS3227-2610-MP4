# Spec Delta

## Purpose

Lets a Reporter file bug reports and read back only the reports they filed, with long-text fields kept as Markdown source exactly as written.

## ADDED Requirements

### Requirement: RPT-01 Reporter creates a report

The system SHALL let an active Reporter create a report with `title`, `description`, `reproductionSteps`, `expectedBehavior`, `actualBehavior` and `environment`. Title and description SHALL be required. The other four SHALL be optional and default to an empty string. The server SHALL assign the ID, Reporter identity, status `SUBMITTED`, version 0, no assignee and creation/update timestamps, then return the created report.

#### Scenario: Valid submission
- **GIVEN** an active Reporter session
- **WHEN** the Reporter posts a non-blank title and description and two optional fields
- **THEN** the response is 201 with the stored report, status `SUBMITTED`, version 0 and no assignee
- **AND** omitted optional fields read back as empty strings
- **AND** the report appears in that Reporter's list

#### Scenario: Server-owned fields cannot be supplied
- **WHEN** the request body contains `id`, `reporterId`, `status`, `version`, `assigneeId` or any other unknown field
- **THEN** the response is 400 `VALIDATION_FAILED` and no report is stored

### Requirement: RPT-02 Report field validation

The server SHALL reject a create request with 400 `VALIDATION_FAILED`, and store nothing, when title or description is missing or blank, when title is longer than 200 UTF-16 code units or contains a line break, when any long-text field is longer than 20,000 UTF-16 code units, or when any field contains U+0000 or an unpaired surrogate.

#### Scenario: Missing or blank required field
- **WHEN** the title or description is absent, empty or whitespace-only
- **THEN** the response is 400 `VALIDATION_FAILED` and the Reporter's report count is unchanged

#### Scenario: Oversized field
- **WHEN** a long-text field has 20,001 UTF-16 code units
- **THEN** the response is 400 `VALIDATION_FAILED`
- **AND** a field of exactly 20,000 code units is accepted

#### Scenario: Text PostgreSQL cannot store faithfully
- **WHEN** any field contains U+0000 or an unpaired surrogate
- **THEN** the response is 400 `VALIDATION_FAILED` and nothing is stored

### Requirement: RPT-03 Markdown source fidelity

The server SHALL store and return every Reporter-authored text field exactly as submitted, without trimming, normalising, rendering or converting it. No Reporter API response SHALL contain server-generated HTML.

#### Scenario: Markdown and markup round-trip unchanged
- **GIVEN** a description containing a fenced code block, a table, trailing spaces and `<script>alert(1)</script>`
- **WHEN** the Reporter creates the report and reads it back
- **THEN** the returned description is identical, character for character, to the submitted text

### Requirement: RPT-04 Reporter lists own reports

The system SHALL return only reports whose stored Reporter is the session actor. Results SHALL be ordered by creation time, newest first, then by ID ascending, and paginated with `page` (default 1) and `pageSize` (default 20, maximum 100). Items SHALL contain only `id`, `title`, `status`, `createdAt` and `updatedAt`.

#### Scenario: Two Reporters are isolated
- **GIVEN** Reporter R1 filed reports A and B and Reporter R2 filed report C
- **WHEN** R1 lists reports
- **THEN** the items are B and A only, newest first, and `totalItems` is 2

#### Scenario: Invalid list parameters
- **WHEN** `page` or `pageSize` is non-numeric, zero, repeated, above the maximum, or an unknown parameter such as `reporterId` is supplied
- **THEN** the response is 400 `VALIDATION_FAILED`

### Requirement: RPT-05 Reporter reads own report

The system SHALL return the full detail, including all six text fields, status, version and timestamps, only when the requested report's stored Reporter is the session actor. A report filed by someone else SHALL be indistinguishable from a missing report.

#### Scenario: Read own report
- **WHEN** a Reporter requests a report they filed
- **THEN** the response is 200 with every source field as stored

#### Scenario: Another Reporter's report
- **WHEN** Reporter R2 requests report A filed by R1
- **THEN** the response is 404 `REPORT_NOT_FOUND` with no report content
- **AND** the status, body shape and message match those for a nonexistent ID

### Requirement: RPT-06 Trusted Reporter identity

Every Reporter endpoint SHALL resolve the actor from the server-side session. A missing or invalid session SHALL return 401 `UNAUTHENTICATED`. An inactive actor or one whose role is not `REPORTER` SHALL return 403 `FORBIDDEN`. Identity or role supplied in the body, query or headers SHALL never select the actor.

#### Scenario: Wrong role or no session
- **WHEN** an unauthenticated caller, an inactive Reporter, a Developer or a Maintainer calls any Reporter endpoint
- **THEN** the response is 401 or 403 respectively, and nothing is created or disclosed

### Requirement: RPT-07 Safe error and caching behavior

Error responses SHALL use the shared envelope `{ code, message, requestId }` with a non-empty fixed message and no report content, SQL, stack trace or submitted text. Every Reporter response SHALL send `Cache-Control: no-store`.

#### Scenario: Failure response is safe
- **WHEN** any Reporter request fails validation, authorization or lookup
- **THEN** the body contains only `code`, `message` and `requestId`, and the response is marked `no-store`
