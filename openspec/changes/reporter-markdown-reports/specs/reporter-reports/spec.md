# Spec Delta

## Purpose

Lets a Reporter file bug reports with a plain-text title and a Markdown description, at a bounded rate, and read back only the reports they filed.

## ADDED Requirements

### Requirement: RPT-01 Reporter creates a report

The system SHALL let an active Reporter create a report with exactly two Reporter-authored fields: `title` and `description`, both required. The server SHALL assign the ID, Reporter identity, status `SUBMITTED`, version 0, no assignee and creation/update timestamps, then return the created report.

#### Scenario: Valid submission
- **GIVEN** an active Reporter session
- **WHEN** the Reporter posts a non-blank title and description
- **THEN** the response is 201 with the stored report, status `SUBMITTED`, version 0 and no assignee
- **AND** the report appears in that Reporter's list

#### Scenario: Server-owned or unsupported fields cannot be supplied
- **WHEN** the request body contains `id`, `reporterId`, `status`, `version`, `assigneeId`, `reproductionSteps`, `environment` or any other key besides `title` and `description`
- **THEN** the response is 400 `VALIDATION_FAILED` and no report is stored

### Requirement: RPT-02 Report field validation

The server SHALL reject a create request with 400 `VALIDATION_FAILED`, and store nothing, when title or description is missing, not a string, or blank, when title is longer than 200 UTF-16 code units or contains a line break, when description is longer than 20,000 UTF-16 code units, or when either field contains U+0000 or an unpaired surrogate.

#### Scenario: Missing or blank required field
- **WHEN** the title or description is absent, null, empty or whitespace-only
- **THEN** the response is 400 `VALIDATION_FAILED` and the Reporter's report count is unchanged

#### Scenario: Oversized field
- **WHEN** the description has 20,001 UTF-16 code units, or the title has 201
- **THEN** the response is 400 `VALIDATION_FAILED`
- **AND** a description of exactly 20,000 and a title of exactly 200 code units are accepted

#### Scenario: Text PostgreSQL cannot store faithfully
- **WHEN** either field contains U+0000 or an unpaired surrogate
- **THEN** the response is 400 `VALIDATION_FAILED` and nothing is stored

### Requirement: RPT-03 Markdown source fidelity

The server SHALL store and return `title` and `description` exactly as submitted, without trimming, normalising, rendering or converting them. No Reporter API response SHALL contain server-generated HTML.

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

The system SHALL return `id`, `title`, `description`, `status`, `version`, `createdAt` and `updatedAt` only when the requested report's stored Reporter is the session actor. A report filed by someone else SHALL be indistinguishable from a missing report.

#### Scenario: Read own report
- **WHEN** a Reporter requests a report they filed
- **THEN** the response is 200 with title and description as stored

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
- **WHEN** any Reporter request fails validation, authorization, rate limiting or lookup
- **THEN** the body contains only `code`, `message` and `requestId`, and the response is marked `no-store`

### Requirement: RPT-08 Report creation rate limit

A Reporter SHALL be able to create at most 3 reports in any rolling 60-second window, counted from their stored creation times. A further valid create SHALL return 429 `RATE_LIMITED` with a `Retry-After` header in whole seconds, and store nothing. The check and insert SHALL be atomic per Reporter. Rejected requests SHALL NOT count.

#### Scenario: Fourth report within a minute
- **GIVEN** Reporter R1 created 3 reports in the last 60 seconds
- **WHEN** R1 posts a fourth valid report
- **THEN** the response is 429 `RATE_LIMITED` with `Retry-After` of at least 1, and R1's report count is still 3

#### Scenario: Window rolls over
- **GIVEN** R1's oldest of 3 creates in the window becomes more than 60 seconds old
- **WHEN** R1 posts a valid report
- **THEN** the response is 201

#### Scenario: Concurrent creates cannot exceed the limit
- **GIVEN** R1 has 2 creates in the current window
- **WHEN** R1 sends 5 valid creates concurrently
- **THEN** exactly 1 returns 201, 4 return 429, and R1 has exactly 3 reports in the window

#### Scenario: Limits are per Reporter and ignore failed requests
- **GIVEN** R1 is at the limit
- **WHEN** R2 posts a valid report, and separately R1 posts an invalid report
- **THEN** R2 gets 201, and R1 gets 400 `VALIDATION_FAILED` rather than 429
- **AND** neither the invalid request nor any 429 adds to R1's window

### Requirement: RPT-09 Reporter edits an unassigned report

A Reporter SHALL be able to replace the `title` and `description` of their own unassigned `SUBMITTED` report, sending exactly those fields and the `version` they last read, validated per RPT-02 and stored per RPT-03. The update SHALL be atomic and succeed only if ownership, no assignee, `SUBMITTED` and the version all hold, then increase the version by 1 and change `updatedAt`. Checks SHALL run: session, validation, existence, lock, version.

#### Scenario: Edit an unassigned report
- **GIVEN** R1 filed report A, which is unassigned with version 0
- **WHEN** R1 sends a valid title and description with version 0
- **THEN** the response is 200 with the new title and description stored exactly, version 1 and a later `updatedAt`

#### Scenario: Stale version
- **WHEN** R1 sends an edit with a version other than the stored one
- **THEN** the response is 409 `VERSION_CONFLICT` and nothing changes

#### Scenario: Assigned or progressed report is locked
- **GIVEN** report A has an assignee or a status other than `SUBMITTED`
- **WHEN** R1 sends an otherwise valid edit with the current version
- **THEN** the response is 409 `REPORT_LOCKED` and nothing changes

#### Scenario: Edit races an assignment
- **GIVEN** R1's unassigned report A
- **WHEN** R1's edit and a Maintainer's assignment of A run concurrently
- **THEN** either the edit returns 200 before the assignment applies, or the edit returns 409 and A keeps the assignment and its original text
- **AND** a report is never edited after it is assigned

#### Scenario: Another Reporter's report or invalid body
- **WHEN** R2 edits R1's report, or any Reporter edits a nonexistent ID
- **THEN** the response is 404 `REPORT_NOT_FOUND` matching the RPT-05 404, and nothing changes
- **AND** a body with a missing, non-integer or negative `version`, an invalid field per RPT-02, or any other key returns 400 `VALIDATION_FAILED`

#### Scenario: Edits are not rate limited
- **GIVEN** R1 is at the RPT-08 create limit
- **WHEN** R1 edits an unassigned report
- **THEN** the edit succeeds and does not change the create count

### Requirement: RPT-10 Reporter closes an unassigned report

A Reporter SHALL be able to close their own unassigned `SUBMITTED` report by sending exactly the `version` they last read. The close SHALL be one atomic step that succeeds only if ownership, no assignee, `SUBMITTED` and the version all hold, and SHALL set status `CLOSED`, increase the version by 1 and change `updatedAt`. A closed report SHALL stay readable and listed. Reopening is not supported.

#### Scenario: Close an unassigned report
- **GIVEN** R1 filed report A, which is unassigned with version 0
- **WHEN** R1 closes A with version 0
- **THEN** the response is 200 with status `CLOSED`, version 1, a later `updatedAt` and the title and description unchanged
- **AND** A still reads with 200 and appears in R1's list with status `CLOSED`

#### Scenario: Closed, assigned or progressed report is locked
- **GIVEN** report A is already `CLOSED`, has an assignee, or has another status than `SUBMITTED`
- **WHEN** R1 closes or edits A with the current version
- **THEN** the response is 409 `REPORT_LOCKED` and nothing changes

#### Scenario: Stale version, another Reporter, invalid body
- **WHEN** R1 closes A with a version other than the stored one
- **THEN** the response is 409 `VERSION_CONFLICT` and nothing changes
- **AND** another Reporter's or a nonexistent report returns the RPT-05 404
- **AND** a body that is not an object with exactly an integer `version` of 0 or more returns 400 `VALIDATION_FAILED`

#### Scenario: Close races an assignment
- **WHEN** R1's close and a Maintainer's assignment of the same unassigned report run concurrently
- **THEN** either the close returns 200 before the assignment applies, or the close returns 409 and the report stays assigned and `SUBMITTED`
- **AND** a report is never closed by the Reporter after it is assigned

#### Scenario: Closing does not free a rate-limit slot
- **GIVEN** R1 created 3 reports in the last 60 seconds and closed one of them
- **WHEN** R1 posts a valid report
- **THEN** the response is 429 `RATE_LIMITED`
