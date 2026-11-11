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
