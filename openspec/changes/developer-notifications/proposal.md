# Developer notifications

## Why

Developers need to notice assignments, reassignments, returned resolutions and reopened reports. Former assignees must not regain access to protected report content.

## What Changes

- Persist minimal notifications transactionally with the Maintainer lifecycle events.
- Show each Developer their own recent notifications with read controls.
- Keep report access governed by current assignment.

## Capabilities

### New Capabilities
- `developer-notifications`: Durable, privacy-preserving Developer lifecycle notifications.

### Modified Capabilities

## Impact

Shared migrations, Maintainer transactions, authenticated Developer API, frontend dashboard and security tests. Existing reports are not backfilled.
