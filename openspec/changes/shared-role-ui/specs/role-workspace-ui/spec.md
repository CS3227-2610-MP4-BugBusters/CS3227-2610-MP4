# Spec Delta

## Purpose
Provide consistent, accessible role workspaces across the bug tracker frontends.

## ADDED Requirements

### Requirement: Role-aware workspace
The Developer and Maintainer frontends SHALL present consistent responsive navigation, identity and content areas using a reusable shell capable of supporting Reporter.

#### Scenario: Authorized role opens workspace
- **WHEN** an active role user opens their workspace
- **THEN** the appropriate role name and available navigation are displayed with their existing content

#### Scenario: Session unavailable
- **WHEN** a user is signed out or denied access
- **THEN** protected report content is not exposed by the shell

### Requirement: Accessible responsive presentation
The workspace SHALL support keyboard navigation, visible focus, labelled controls and narrow screens while preserving existing error and loading states.

#### Scenario: Narrow viewport
- **WHEN** the workspace is displayed on a narrow viewport
- **THEN** navigation and content remain reachable without horizontal overflow

### Requirement: Shared role-aware sign-in entry
The root entry SHALL authenticate using the existing session service and route an active Developer to `/developer/`, an active Reporter to `/reporter/`, and an active Maintainer to the Maintainer queue. Inactive accounts SHALL not be routed. Client routing SHALL NOT replace server-side authorization.

#### Scenario: Active role signs in
- **WHEN** an active account signs in at the root entry
- **THEN** it opens its corresponding role workspace without fetching another role's protected data

#### Scenario: Inactive account
- **WHEN** an inactive account is returned by the session service
- **THEN** it is denied access and no role dashboard is opened

#### Scenario: Signed-out role workspace
- **WHEN** a Developer or Reporter workspace has no session, loses its session, or completes logout
- **THEN** it clears protected content and returns to `/` without displaying a separate credential form
- **AND** failed logout retains an actionable error without claiming success

#### Scenario: Account presentation and sign-out
- **WHEN** an authenticated account opens Account or updates its display name
- **THEN** the shared header displays its name once, the account section displays username and role, and navigation returns to that role's dashboard
- **AND** Account exposes its sign-out control and successful logout returns to `/`
