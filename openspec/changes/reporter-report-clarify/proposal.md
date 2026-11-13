# Proposal

## Why

Reporters often file vague drafts ("it breaks after the second one", "using chrome
i think") and leave out the steps, expected behavior or error text that Developers
need. Developers and Maintainers then have to ask later. The Reporter change keeps a
report to a title and a Markdown description, and leaves suggestions for missing
details to a future Reporter AI feature. The gateway's `REPORTER_ASSIST` task has no
consumer yet.

A plan to have the AI restructure the whole draft was dropped. Live trials showed
models summarising long drafts and losing most of the Reporter's content (see
`logs/reporter-report-clarify/trial-structure-2026-11-12.md`). This change only asks
questions and never edits the draft.

## What Changes

- Add a **Check my report** action to the **New Report** screen only. It runs only
  when the Reporter requests it, and never on submitted reports.
- The server sends the unsaved draft title and description to the SoC LLM through
  the shared gateway (`REPORTER_ASSIST`). It returns two lists:
  - **Unclear parts:** an exact quote from the draft plus a question that would make
    it clear. The server rejects any quote that does not appear word for word in the
    draft.
  - **Missing information:** a category from a fixed list (Summary, Steps to
    reproduce, Expected behavior, Actual behavior, Environment, Frequency, Error
    messages and logs) plus a question asking for it.
- The Reporter reads the questions and edits the draft themselves. The AI never
  changes the draft, and nothing is stored on the server before Submit.
- Gateway support for this task:
  - A Reporter-specific model setting, `SOC_LLM_MODEL_REPORTER`, falling back to
    `SOC_LLM_MODEL`, like the Developer override. The intended model is `x-test-2`
    (owner decision), a provider test slot whose backing model can change.
  - No `reasoning_effort` is sent (owner decision), so the provider default applies.
    The request shape stays the same for every role.
  - A larger `REPORTER_ASSIST` policy so a full 20,000-character draft fits, with
    output room for default reasoning.
- **BREAKING for the in-flight `socllm-per-task-limits` spec:** raising the transport
  ceilings changes its TL-02 values and approved tests.

Out of scope: rewriting, restructuring or auto-inserting text; AI on submitted
reports; title suggestions; storing questions or drafts; and Developer and Maintainer
limits, which keep their current behavior.

## Capabilities

### New Capabilities

- `reporter-report-clarify`: An on-demand AI check of a new report's draft that
  points out unclear parts with exact quotes and lists missing information, without
  changing the draft.

### Modified Capabilities

None in `openspec/specs/`. The `socllm-task-limits` capability is still in the
in-flight `socllm-per-task-limits` change. Its ceilings are updated there through a
task in this change.

## Impact

- Backend (`src/backend/platform`): a new Reporter-only endpoint
  `POST /api/reporter/report-clarify`, the Reporter model override in
  `SocLlmRoleConfiguration`.
- Gateway (`src/backend/socllm`): transport ceilings in `SocLlmHttpProvider` and
  `SocLlmTaskPolicy`, and the `REPORTER_ASSIST` policy in `SocLlmTaskPolicies`. The
  request body shape is unchanged.
- Frontend (`src/frontend/reporter`): `NewReport.tsx` gets Check my report and a
  results panel.
- Config: `SOC_LLM_API_KEY_REPORTER`, optional `SOC_LLM_MODEL_REPORTER`, and
  `SOC_LLM_TPM` large enough for the Reporter reservation.
- Security: an AI-security change with untrusted draft text and model output. It
  needs a separate `security_reviewer`. Live use stays off until a model and limits
  are confirmed by a bounded trial.
