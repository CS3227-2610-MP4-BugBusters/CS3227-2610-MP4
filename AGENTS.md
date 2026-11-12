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

- Select a proportionate path using [workflow selection](workflow/workflow-selection.md)
  and follow the applicable [TDD workflow](workflow/tdd.md). Before starting
  Red, confirm that the contract, fixtures, and runner are available.
- For the full path, use the named roles in `.codex/agents/`. Spawn `test_designer` and then
  a fresh `baseline_verifier`. After the verifier confirms Red, summarize
  its verdict and resolve any open decisions. Proceed when implementation has
  already been authorized and the baseline matches the agreed behavior; otherwise
  obtain the feature owner's approval. The verifier checks tests and evidence.
  After implementation, spawn a
  fresh `candidate_verifier`. Authorization and AI security changes also
  require a separate `security_reviewer`.
- Give specialists the task ID, relevant paths, starting revision, allowed
  edits, and expected evidence. Specialists work within that scope, return
  their findings, and do not dispatch other agents.
- If delegation required by the selected path is unavailable, report it before
  editing application code; do not claim the independent review was completed.

## Verification

- For Developer frontend changes, run `npm run verify` from
  `src/frontend/developer`.
- For Maintainer frontend changes, read the
  [OpenSpec baseline](openspec/specs/maintainer-report-browser/spec.md), then
  run `npm run verify` from `src/frontend/maintainer`.
- For checklist persistence changes, use the disposable PostgreSQL setup in
  `src/backend/developer/README.md` when the backend is present.
- Start evidence logs with a plain-language summary of the goal, changes,
  checks and results, review findings and fixes, and remaining limitations.
  Follow it with the detailed reflection format in the TDD workflow, including
  agent coordination, context supplied, review findings and fixes, test scope,
  resource measurements and limitations. Put commands, revisions and hashes
  after the summary. Do not invent missing details or measurements.
- Record the checks run, the revision tested, and any blocked checks using
  the evidence format in the TDD workflow. Mark an OpenSpec task complete
  only when its stated checks have passed.
- For future agent runs, record the process and available usage measurements
  as specified in `workflow/tdd.md`. Keep handoffs focused and refer to
  sanitized trace locations instead of copying full transcripts into prompts.
