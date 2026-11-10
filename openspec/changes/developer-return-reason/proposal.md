# Show Maintainer return reason

## Why

When a Maintainer returns a proposed resolution, the assigned Developer needs the recorded reason to continue the investigation. The current Developer report detail omits it.

## What Changes

- Show the latest recorded resolution return reason in report detail while the report is in progress.
- Preserve current assignment authorization and render the reason as plain text.
- Leave notification delivery, other history and lifecycle transitions outside this change.

## Capabilities

### New Capabilities
- `developer-return-reason`: Authorized read and display of a returned resolution reason.

### Modified Capabilities

## Impact

Developer report detail API and frontend, using existing Maintainer report history; no schema migration.
