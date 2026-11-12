# Spec Delta

## Purpose

Provide team-controlled testing and deployed accounts with one fixed role, without
allowing public clients to create privileged identities or expose credentials.

## ADDED Requirements

### Requirement: AUTH-01 Controlled fixed-role provisioning

The system SHALL permit account creation through a team-operated provisioning
command or an authenticated Maintainer account-creation page/API. Other roles
and anonymous clients SHALL NOT create accounts. Each account SHALL have a stable UUID and exactly one immutable role:
REPORTER, DEVELOPER or MAINTAINER. Public signup and role-change APIs SHALL NOT exist.

#### Scenario: Provision all three roles

- **WHEN** the operator provisions distinct valid accounts for each role
- **THEN** each persists with its assigned role and can authenticate
- **AND** repeating an existing username fails without replacing its ID, password or role

#### Scenario: Self-promotion attempt

- **WHEN** a non-Maintainer attempts account creation, or a client supplies actor overrides or calls public signup/role-change routes
- **THEN** it gains no account or privilege and stored roles remain unchanged

### Requirement: AUTH-02 Credential and account lifecycle safety

The system SHALL persist only adaptive password hashes and protect passwords,
sessions and CSRF tokens from public output. The operator SHALL be able to disable
an ordinary (non-demo) account or reset its password without changing its role or UUID. Existing
sessions SHALL cease authorizing subsequent requests after either change commits.

#### Scenario: Secret-safe provisioning and reset

- **WHEN** the operator creates or resets an account using a secret input channel
- **THEN** no plaintext password is stored in the database, arguments or logs
- **AND** other accounts and the target's role/UUID are unchanged

#### Scenario: Existing session after account change

- **GIVEN** an authenticated account has an existing session
- **WHEN** disablement or password reset commits and that session requests protected data
- **THEN** access fails without protected data or domain mutation

### Requirement: AUTH-08 Fixed shared demo Maintainer

Application startup SHALL create one active `demo.maintainer` with display name
`Demo Maintainer`, password `demo-maintainer-2026` and role MAINTAINER. These are
public demo credentials. Startup SHALL be idempotent and SHALL NOT replace an
existing account. This account's name, password, role and active state SHALL be
immutable through web and operator commands, including after later restarts.

#### Scenario: First startup and subsequent restart

- **WHEN** a fresh application starts and later restarts against the same database
- **THEN** the same seeded Maintainer can log in with the documented credentials
- **AND** conflicting existing identities fail startup rather than being overwritten

#### Scenario: Protect shared access

- **WHEN** a user or operator tries to change or disable the seeded account
- **THEN** the change fails and its existing name and credentials remain usable

### Requirement: AUTH-09 Maintainer account creation and own password change

An active Maintainer SHALL create fixed-role accounts through the Account page.
Every active non-demo account SHALL change its own password using its current
password and a new 12–72 UTF-8 byte password. The server SHALL derive the target
from the session, enforce CSRF and reject extra fields. A successful change SHALL
revoke all sessions. Each role SHALL link to the shared page.

#### Scenario: Create and personalize credentials

- **WHEN** a Maintainer creates an account for any of the three roles
- **THEN** that account can sign in, change its own password, and sign in again
- **AND** duplicate usernames fail without replacing accounts
- **AND** old passwords and sessions cease to authorize access after the change

#### Scenario: Deny unauthorized account management

- **WHEN** an anonymous or wrong-role caller creates an account, a request lacks CSRF, or a password change supplies another account's identity
- **THEN** the request fails without account changes
