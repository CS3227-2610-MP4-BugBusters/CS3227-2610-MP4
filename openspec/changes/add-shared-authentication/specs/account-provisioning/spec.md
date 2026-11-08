# Spec Delta

## Purpose

Provide team-controlled testing and deployed accounts with one fixed role, without
allowing public clients to create privileged identities or expose credentials.

## ADDED Requirements

### Requirement: AUTH-01 Controlled fixed-role provisioning

The system SHALL permit account creation only through a team-operated provisioning
command. Each account SHALL have a stable UUID and exactly one immutable role:
REPORTER, DEVELOPER or MAINTAINER. Public signup and role-change APIs SHALL NOT exist.

#### Scenario: Provision all three roles

- **WHEN** the operator provisions distinct valid accounts for each role
- **THEN** each persists with its assigned role and can authenticate
- **AND** repeating an existing username fails without replacing its ID, password or role

#### Scenario: Self-promotion attempt

- **WHEN** a browser supplies role/actor overrides or calls signup/role-change routes
- **THEN** it gains no account or privilege and stored roles remain unchanged

### Requirement: AUTH-02 Credential and account lifecycle safety

The system SHALL persist only adaptive password hashes and protect passwords,
sessions and CSRF tokens from public output. The operator SHALL be able to disable
an account or reset its password without changing its role or UUID. Existing
sessions SHALL cease authorizing subsequent requests after either change commits.

#### Scenario: Secret-safe provisioning and reset

- **WHEN** the operator creates or resets an account using a secret input channel
- **THEN** no plaintext password is stored in the database, arguments or logs
- **AND** other accounts and the target's role/UUID are unchanged

#### Scenario: Existing session after account change

- **GIVEN** an authenticated account has an existing session
- **WHEN** disablement or password reset commits and that session requests protected data
- **THEN** access fails without protected data or domain mutation
