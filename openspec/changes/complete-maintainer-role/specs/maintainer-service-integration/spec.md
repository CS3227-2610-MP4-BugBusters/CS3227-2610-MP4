# Spec Delta

## Purpose

Connect Maintainer screens to trusted shared services without confusing local
synthetic behavior with server authorization, persistence or release verification.

## ADDED Requirements

### Requirement: MNT-SEC-01 Trusted authorization and concurrency

The server SHALL authorize the current active Maintainer for each role operation,
derive identity from trusted authentication and reject actor/role spoofing. Unsafe
cookie-session requests SHALL require CSRF protection. Mutations SHALL recheck
eligibility, expected versions and state atomically and reject stale writes with 409.

#### Scenario: X1 Unauthorized request

- **WHEN** an unauthenticated, inactive or wrong-role caller requests Maintainer data or actions
- **THEN** access is denied without protected content, domain writes or provider calls
- **AND** spoofed actors and missing/invalid CSRF cannot bypass the server checks

#### Scenario: SEC-06 Concurrent writes or partial failure

- **WHEN** conflicting clients submit the same version or a transaction fails mid-operation
- **THEN** only an eligible matching write commits and failures leave no partial data/history
- **AND** stale callers cannot overwrite the winning state

### Requirement: MNT-READ-SERVER Authorized query integration

The real read service SHALL provide authorized report lists, detail/history and
eligible Developer queries through agreed safe projections. Maintainer report
detail SHALL omit private checklist drafts and draft revisions, as well as Developer
private notes and their revision, including when nested in another response.

#### Scenario: Q1 Query real report evidence

- **WHEN** a current active Maintainer uses valid filters or opens a report
- **THEN** persisted matching data, stable ordering and safe detail fields are returned
- **AND** malformed queries fail safely and no private checklist draft or Developer private notes or revision are disclosed

### Requirement: MNT-INTEGRATE-01 Real adapters and environment separation

The release SHALL use actual authenticated services and persistence with separate
development/production databases, credentials and configuration. Synthetic adapters
SHALL NOT substitute for unavailable production services. The deployed revision
SHALL be identifiable and verification SHALL distinguish deterministic and live AI runs.

#### Scenario: MT-25 Integrated report flow

- **WHEN** separate Reporter, Developer and Maintainer sessions complete create, assign,
  investigate, share, respond, propose and review/close
- **THEN** persisted state/history and permissions match the winning operations
- **AND** denial controls are verified through real APIs as well as the browser

#### Scenario: MT-26 Release verification

- **WHEN** a known revision is deployed through team-managed automation
- **THEN** authorized login/read and wrong-role denial succeed as specified in each environment
- **AND** sanitized evidence identifies distinct environments and the revision without secret values

### Requirement: MNT-READ-EVIDENCE Shared investigation evidence

The Maintainer detail SHALL retain authorized shared investigation notes/history
required by the report plan. Additional Developer private notes SHALL remain a
separate field and SHALL NOT replace the shared evidence contract.

#### Scenario: Shared and private notes coexist

- **GIVEN** a report has shared investigation evidence and separate private notes
- **WHEN** an active Maintainer opens its detail
- **THEN** authorized shared evidence is readable and private text/revision are absent
- **AND** any change to the shared evidence contract is reconciled with its owner before implementation
