# MarkdownView test folder

Moved all ten MarkdownView test files from the shared Markdown package's `src/`
folder to `test/`, updated their source imports and mock paths, and included
`test/` in TypeScript checking. Application code and test assertions are unchanged.
TypeScript checking and all 242 tests across 12 files passed before and after the
move. No behavior task was completed or marked complete by this reorganization.
No independent review was performed; no blocked checks remain.

## Technical evidence

- Run: `2026-11-09-markdown-view-test-folder`.
- Related change: `reporter-markdown-reports`, group 3 shared Markdown tests.
- Starting revision: `79d8ab7d39df6176c3923eae9dd95d4b26adf799`, with existing
  staged and unstaged work preserved. Candidate: working tree at that revision.
- Context: change tasks, design, safe-markdown spec, package configuration,
  test imports and `workflow/tdd.md`.
- Role: primary agent. Actual model, reasoning effort and token usage unavailable.
- Command before and after: `npm.cmd run verify` from
  `src/frontend/shared/markdown`.
- Both runs: exit 0; `tsc --noEmit` passed; Vitest reported 12 passing files and
  242 passing tests.
- Initial `npm run verify` invocation was blocked by PowerShell's script policy
  for `npm.ps1`; invoking `npm.cmd` completed successfully.
- This is a test-file relocation, with no behavior change or new Red baseline.
