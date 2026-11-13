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

## Shared sign-in presentation

Use a shared presentation-only sign-in component with role-specific introductory copy. Developer and Maintainer keep their existing authentication, error, busy and session-refresh logic. The shared interface accepts controlled username and password, submit and change callbacks, busy state and optional status/error content. It never chooses a role, calls an API, redirects, or authorizes a user. Reporter may consume it once its own frontend exists. Keep the existing shared role workspace as the dashboard frame.

## Shared root entry follow-up

The user has requested a role-neutral sign-in at `/`. Use the existing server session identity to route active users to their role frontend. Preserve server-side role authorization, sign-out behavior, the Maintainer Markdown provider and direct role workspace access. Shared presentation remains independent of authentication decisions.
