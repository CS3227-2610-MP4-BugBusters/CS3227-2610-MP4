# Spec Delta

## Purpose

Establish trusted identity for every role through revocable server-side sessions,
with safe browser integration and independently testable authentication failures.

## ADDED Requirements

### Requirement: AUTH-03 Login and bounded failure

Valid credentials for an active provisioned account SHALL establish a server-side
session and rotate any pre-login session identifier. Invalid, unknown or inactive
credentials SHALL receive the same safe failure shape. Login attempts SHALL be
rate-limited using shared bounded state, without permanent account lockout.

#### Scenario: Login for each role

- **WHEN** an active account supplies valid credentials and CSRF protection
- **THEN** it receives an authenticated session and its own safe user projection
- **AND** a pre-login session identifier cannot authorize protected requests

#### Scenario: Invalid or excessive login attempts

- **WHEN** credentials are wrong, unknown or inactive, or the configured attempt budget is exhausted
- **THEN** authentication fails with a generic 401 or budget-specific 429 and no protected content
- **AND** failures do not create authenticated sessions or change role/report data

### Requirement: AUTH-04 Trusted current identity

Every protected request SHALL derive identity from the server session and current
account record. Missing/expired sessions SHALL yield 401; inactive or wrong-role
actors SHALL be denied without content. Identity lookup failure SHALL fail closed.
Current-user responses SHALL expose only UUID, display name, role and active state.

#### Scenario: Resolve current user

- **WHEN** a valid session requests current identity
- **THEN** the response reflects its current account and contains no credentials or session identifier
- **AND** client-supplied user IDs, roles and synthetic bearer headers cannot select another actor

#### Scenario: Role and account denial

- **WHEN** an unauthenticated, inactive, revoked or wrong-role caller requests a role endpoint
- **THEN** the documented denial occurs before protected data, domain writes or AI calls
- **AND** an unavailable account/session store does not fall back to a fake actor

### Requirement: AUTH-05 Logout expiry and transport

Logout SHALL invalidate the server session. Idle and absolute expiration SHALL
prevent later access. Production session cookies SHALL be Secure, HttpOnly,
SameSite=Lax, host-only and unavailable in URLs. Session responses SHALL use no-store;
cross-origin credentialed access SHALL NOT be enabled by default.

#### Scenario: Logout and replay

- **WHEN** a user logs out successfully and replays the old session cookie
- **THEN** protected access returns 401 and no prior session is restored

#### Scenario: Session limits and cookie flags

- **WHEN** idle or absolute expiry passes, or a production session is issued
- **THEN** expired sessions cannot authorize requests and issued cookies have the required flags
- **AND** an identifier in a query string is not accepted as authentication

### Requirement: AUTH-06 CSRF-protected unsafe requests

Unsafe cookie-session operations, including login and logout, SHALL require a
session-bound CSRF token. Missing, foreign or stale tokens SHALL yield safe denial
without mutation. Safe methods SHALL NOT perform account or report mutations.

#### Scenario: CSRF rejection and valid recovery

- **WHEN** login, logout or a protected write lacks a valid token for that session
- **THEN** it fails without changing authentication or report state
- **WHEN** the client obtains the current token and repeats a permitted explicit action
- **THEN** the CSRF layer permits the request, subject to feature authorization and versions

### Requirement: AUTH-07 Real role integration

Shared authentication SHALL supply trusted actors to feature authorization without
replacing report ownership/assignment checks. Real-session integration tests SHALL
exercise at least one existing Developer read and the Maintainer identity consumer.
Synthetic-session harnesses SHALL remain explicitly separate evidence.

#### Scenario: Real sessions cross the role boundary

- **GIVEN** separate provisioned Reporter, Developer and Maintainer accounts
- **WHEN** real login sessions request an assigned Developer list and Maintainer identity
- **THEN** correct identities reach the adapters and wrong-role requests expose no reports
- **AND** spoofed headers cannot change the actor or bypass current assignment
