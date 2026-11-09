# Design

## Context
Developer and Maintainer are separate React/Vite packages. Reporter is absent. Existing Developer AI edits are uncommitted and must be preserved.

## Goals / Non-Goals
**Goals:** Consistent responsive role shell and reusable visual foundation, including a Reporter-compatible interface.

**Non-Goals:** New report actions, fabricated Reporter data, API changes or changes to authorization.

## Decisions
Use shared presentation code under `src/frontend/shared` consumed by both existing apps. Use the approved `workflow/shared-frontend-design.md` for styling and accessible states. Render existing role content inside the shell without exposing protected data before session checks. Do not copy implementation from previous projects.

## Risks / Trade-offs
Separate build systems must resolve shared files in development and production. Reporter integration requires its owner and actual app. Existing tests and accessibility behavior must be preserved.
