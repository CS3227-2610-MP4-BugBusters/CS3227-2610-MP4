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
  a fresh `baseline_verifier`. After the verifier confirms Red, summarize
  its verdict and any open decisions, then ask the feature owner whether
  to proceed with implementation. The verifier checks the tests and
  evidence. Treat a clear go-ahead as approval to use that baseline.
  After implementation, spawn a
  fresh `candidate_verifier`. Authorization and AI security changes also
  require a separate `security_reviewer`.
- Give specialists the task ID, relevant paths, starting revision, allowed
  edits, and expected evidence. Specialists work within that scope, return
  their findings, and do not dispatch other agents.
- If delegation is unavailable, report it before editing application code.

## Verification

- For Developer frontend changes, run `npm run verify` from
  `src/frontend/developer`.
- For checklist persistence changes, use the disposable PostgreSQL setup in
  `src/backend/developer/README.md` when the backend is present.
- Start evidence logs with a plain-language summary of the goal, changes,
  checks and results, review findings and fixes, and remaining limitations.
  Put commands, revisions, hashes, and agent details afterward for reviewers.
- Record the checks run, the revision tested, and any blocked checks using
  the evidence format in the TDD workflow. Mark an OpenSpec task complete
  only when its stated checks have passed.
