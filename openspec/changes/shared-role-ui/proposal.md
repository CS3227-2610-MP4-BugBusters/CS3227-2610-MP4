# Proposal

## Why
Role frontends need a consistent, accessible workspace while preserving their existing behavior.

## What Changes
- Introduce shared role-aware navigation and visual tokens for the existing Developer and Maintainer apps.
- Provide a reusable Reporter shell for the future Reporter application; its report features remain owned by that role.
- Preserve existing authorization, data and form behavior.

## Capabilities
### New Capabilities
- `role-workspace-ui`: Responsive shared role workspace and presentation states.

### Modified Capabilities
None.

## Impact
React/Vite frontends and shared presentation code. No API, database or lifecycle changes.
