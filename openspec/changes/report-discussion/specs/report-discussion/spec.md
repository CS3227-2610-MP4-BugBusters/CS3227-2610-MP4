# Spec Delta

## Purpose

Gives each report one shared conversation that its Reporter, its assigned Developer and Maintainers can read and add Markdown comments to, without changing the report itself.

## ADDED Requirements

### Requirement: DSC-01 Who can read and post

The system SHALL let these actors read a report's comments and post to it: the Reporter who filed it, the Developer currently assigned to it, and any Maintainer. Each actor SHALL be active and resolved from the server-side session. Any other actor SHALL get the response that actor's role gets for a report it cannot see. Identity in the body, query or headers SHALL never select the author.

#### Scenario: Each participant can post and read
- **GIVEN** report A filed by Reporter R1 and assigned to Developer D1
- **WHEN** R1, D1 and Maintainer M1 each post a valid comment and then read the thread
- **THEN** each post returns 201, and each reader sees all three comments in order

#### Scenario: Non-participants cannot see the thread
- **WHEN** Reporter R2, who did not file A, or Developer D2, who is not assigned to A, reads or posts to A's thread
- **THEN** the response matches that role's response for a report it cannot see (for a Reporter, the RPT-05 404), and nothing is stored or disclosed

#### Scenario: Reassignment changes who can post
- **GIVEN** D1 posted on A, then A is reassigned to D2
- **WHEN** D1 and D2 read or post
- **THEN** D1 is refused as a non-participant, D2 can read and post, and D1's earlier comment stays in the thread

#### Scenario: Wrong session or spoofed author
- **WHEN** there is no session, the actor is inactive, or the body names an `authorId` or role
- **THEN** a missing session returns 401, an inactive actor 403, an author field 400 `VALIDATION_FAILED`, and nothing is stored

### Requirement: DSC-02 Comments never change the report

Posting a comment SHALL NOT change the report's `version`, `updated_at`, status, assignee, title, description or proposal. Comments SHALL be allowed in every report status, including `CLOSED`.

#### Scenario: Version and queue position unchanged
- **GIVEN** report A at version 3 with a known `updated_at`
- **WHEN** any participant posts a comment
- **THEN** A's version, `updated_at`, status and proposal are unchanged
- **AND** a Reporter edit of A carrying version 3 still succeeds when A is otherwise editable

#### Scenario: Comment on a closed report
- **GIVEN** report A has status `CLOSED`
- **WHEN** a participant posts a valid comment
- **THEN** the response is 201 and A stays `CLOSED`

### Requirement: DSC-03 Comment body is safe Markdown

A comment body SHALL be a required, non-blank string of at most 20,000 UTF-16 code units, containing no U+0000 and no unpaired surrogate, stored and returned exactly as submitted. Every role's view SHALL render bodies through the shared `safe-markdown` renderer, and SHALL write them with the shared Write | Preview editor. Author names and times SHALL render as escaped plain text.

#### Scenario: Markdown renders safely in every role
- **GIVEN** a comment body containing a list, a fenced code block, `<img src=x onerror=alert(1)>` and `[x](javascript:alert(1))`
- **WHEN** the Reporter, Developer and Maintainer views show it
- **THEN** each shows the same formatted list and code block, the HTML as literal text, the link as text with its destination, and no `img`, `a`, `script` or event-handler attribute

#### Scenario: Invalid body
- **WHEN** the body is missing, blank, longer than 20,000 code units, not a string, or contains U+0000 or an unpaired surrogate, or the request has any key other than `body`
- **THEN** the response is 400 `VALIDATION_FAILED` and nothing is stored
- **AND** a body of exactly 20,000 code units is accepted

#### Scenario: Preview matches the posted comment
- **WHEN** a participant previews a draft comment and then posts it
- **THEN** the preview DOM equals the rendered comment in the thread

### Requirement: DSC-04 Append-only thread and its shape

Comments SHALL be append-only: there SHALL be no edit or delete operation. A comment SHALL be returned as `id`, `body`, `author` (`id`, `displayName`, `role`) and `createdAt`. The thread SHALL be ordered by `createdAt`, then `id`, and paginated with the same `page` and `pageSize` rules as RPT-04.

#### Scenario: Thread order and shape
- **GIVEN** three comments posted in sequence
- **WHEN** a participant reads the thread
- **THEN** they appear oldest first with exactly the fields above, and `totalItems` is 3

#### Scenario: No edit or delete
- **WHEN** any actor sends PUT, PATCH or DELETE to a comment or thread path
- **THEN** the response is 405 `METHOD_NOT_ALLOWED` and the thread is unchanged

### Requirement: DSC-05 Posting rate limit

An author SHALL be able to post at most 10 comments in any rolling 60-second window, across all reports, counted from their stored comments. A further valid post SHALL return 429 `RATE_LIMITED` with `Retry-After` in whole seconds and store nothing. The check and insert SHALL be atomic per author. Rejected requests SHALL NOT count.

#### Scenario: Eleventh comment within a minute
- **GIVEN** an author posted 10 comments in the last 60 seconds
- **WHEN** they post an eleventh valid comment
- **THEN** the response is 429 `RATE_LIMITED` with `Retry-After` of at least 1, and they still have 10 comments in the window

#### Scenario: Concurrent posts cannot exceed the limit
- **GIVEN** an author has 8 comments in the window
- **WHEN** they send 5 valid posts concurrently
- **THEN** exactly 2 return 201 and 3 return 429

### Requirement: DSC-06 Timeline and private data

Each role's report view SHALL show comments interleaved with the report's history events by time. Developer private notes, checklist drafts and draft revisions SHALL NEVER appear in a comment, a thread response or the timeline. Error responses SHALL use the shared envelope, and every discussion response SHALL send `Cache-Control: no-store`.

#### Scenario: Timeline merges comments and events
- **GIVEN** A was assigned, then commented on, then closed, then commented on
- **WHEN** a participant opens A
- **THEN** the timeline shows those four entries in that order

#### Scenario: Private Developer data stays private
- **GIVEN** D1 saved private notes and a checklist draft on A
- **WHEN** R1 or M1 reads the thread or timeline
- **THEN** neither the notes, the draft steps nor the draft revision appear anywhere in the response
