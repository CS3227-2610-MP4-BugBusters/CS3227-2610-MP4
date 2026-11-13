# Proposal

## Why

Developers can keep private notes, but cannot publish investigation findings to the Maintainer's existing shared-notes view. Provide a separate explicitly shared note without exposing private notes.

## What Changes

- Add a separate per-report editable shared-note draft with explicit Save and Share actions.
- Publish only the last saved draft to the Maintainer's report detail; preserve existing private notes and unrelated shared evidence.
- Enforce current assignment, role, revisions and atomic lifecycle rules when sharing; Reporter access is excluded.
- Record verification, specialist coordination and available measurements without inventing usage or savings.

## Capabilities

### New Capabilities

- `developer-shared-investigation-notes`: Maintainer-visible Developer investigation notes with separate private draft and explicit publication.

### Modified Capabilities

None. The Maintainer browser already supports shared investigation evidence.

## Impact

Developer frontend and backend APIs, shared database migrations, Maintainer shared-evidence projection, authenticated cross-role tests and evidence logs.
