# MP4 agent instructions

## Before editing

- Identify the OpenSpec change and task that match the request. Read the
  relevant spec, design, and tasks under `openspec/changes/`.
- For Developer checklist work, also read the
  [checklist storage contract](workflow/developer/checklist-storage-contract.md).
- If the spec, contract, and tests disagree, ask the feature owner to
  resolve the conflict before implementing the affected behavior.
- Keep edits scoped to the task. Preserve existing conventions and avoid
  unrelated cleanup.

## Behavior changes

- Follow the [TDD workflow](workflow/tdd.md). Before starting Red, confirm
  that the contract, fixtures, and runner needed for the selected test are
  available.
- Use the named roles in `.codex/agents/`. Spawn `test_designer` and then
  a fresh `baseline_verifier`. Wait for a verified Red and feature owner
  approval before spawning `implementer`. After implementation, spawn a
  fresh `candidate_verifier`. Authorization and AI security changes also
  require a separate `security_reviewer`.
- Give specialists the task ID, relevant paths, starting revision, allowed
  edits, and expected evidence. Specialists work within that scope, return
  their findings, and do not dispatch other agents.
- If delegation is unavailable, report it before editing application code.

## Verification

- For Developer frontend changes, run `npm run verify` from
  `src/frontend/developer`.
- For Maintainer frontend changes, read the
  [OpenSpec baseline](openspec/specs/maintainer-report-browser/spec.md), then
  run `npm run verify` from `src/frontend/maintainer`.
- For checklist persistence changes, use the disposable PostgreSQL setup in
  `src/backend/developer/README.md` when the backend is present.
- Record the checks run, the revision tested, and any blocked checks using
  the evidence format in the TDD workflow. Mark an OpenSpec task complete
  only when its stated checks have passed.
