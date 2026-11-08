# Private notes test preparation

## Human-readable summary

- **Goal:** Prepare tests for private Developer notes before implementation.
- **Prepared:** A test plan covers saving and clearing, stale revisions, reassignment, unauthorized users, proposal preservation and state guards.
- **Review:** An independent verifier identified that the privacy case should explicitly include unrelated and former-assignee Developers. The test designer added those cases; the verifier confirmed the correction.
- **Result:** No runnable Red exists yet. The notes API contract, storage fixture and synthetic-session route are missing; shared session integration and Maintainer alignment remain open. No application code was changed.

## Technical evidence

- Starting Git HEAD `79f4c9e51dd2395cca24a8d001db1d9aa9c805a0`; existing local skill-file changes and other untracked files were preserved.
- `private_notes_test_designer` (GPT-6 Luna low) read the private-notes OpenSpec, checklist contract and test infrastructure. It created `openspec/changes/developer-private-investigation-notes/test-plan.md`.
- `private_notes_baseline_verifier` (GPT-6 Luna low) reviewed the plan against DN-01–04 and inspected the existing checklist-only harness. It reported BLOCKED rather than claiming Red. After the N5 amendment, it independently confirmed coverage and final test-plan SHA-256 `afdefbde2f0caaa3504e73184b2ad87c032e264314d939bbd401c44c0c460720`.
- No executable notes tests or fixtures were run. The necessary API, error and text-bound decisions, notes persistence/fixture, trusted actor seam and Maintainer alignment are recorded in the plan. Token usage, timing and costs unavailable.
