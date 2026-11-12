# Maintainer safe Markdown integration — 12 November 2026

Implemented description formatting with the existing shared renderer, while keeping
other report fields literal. Updated the Maintainer setup and guide contributions,
and the CI/image dependency order. Kept deployment and team guide/site assembly
open; did not repeat the documentation or AI repairs merged in PR #17.

## Scope and process

Used `reporter-markdown-reports` tasks 4.1–4.3 and MNT-READ-05/QR-07 on
`0747a59b684f6118f125931db36c8529984ec17b` (merged Developer adoption, PR #18).
Selected the full workflow because this integrates untrusted content rendering.
Used the active ownership table on page 2 of `cs3227 Project (1).pdf` and
`overview.md`: maintained our role contributions; left final documentation
coordination with the Reporter owner. Did not use the crossed-out proposed split.

Supplied specialists the task, spec/source paths, revision, edit boundaries and
required checks. Used test_designer, fresh baseline_verifier, implementer, fresh
candidate_verifier and security_reviewer roles. Kept tests separate from source
implementation; reviewers did not edit either. Supplied focused context rather
than the conversation transcript.

## Red and correction

Reproduced Red with:

```sh
cd src/frontend/maintainer
npm exec vitest run src/App.test.tsx -t "formats only the description as safe Markdown"
```

The plaintext baseline rendered zero list items instead of two. The first test
version also incorrectly demanded no trailing newline in fenced code. Corrected
that assertion with `trimEnd()`, matching the shared renderer's CommonMark output;
did not change application behavior to satisfy the faulty expectation. A fresh
baseline verifier reproduced the same missing-formatting Red after correction.
Preserved the corrected approved test through implementation:
`160A945E5124A4C688E5BF594A311A284ABB85194D590096B5C8DB999DD466A0`
(SHA-256 of `src/frontend/maintainer/src/App.test.tsx`). Proceeded under the
owner's existing implementation authorization; no product decision was open.

## Checks and review

- Dependency-only baseline: `npm run verify` passed all 115 existing tests,
  typechecking and production build before changing rendering.
- `python -m unittest discover -s src/backend/platform/deployment/test -p 'test_*.py'`:
  passed 13 checks.
- `python workflow/security/check.py --lint actionlint`: passed.
- `openspec validate reporter-markdown-reports --strict --no-interactive`: passed.
- Checked the updated guide/setup links against repository paths.

- Candidate `e37f65d`: implementer passed `npm run verify` (116 tests in 18
  files, typechecking and production build including a separate Markdown worker).
  Added a scoped TypeScript suppression for the existing JavaScript Vite plugin.
- Browser inspection found that the shared component has no wrapper class;
  added the explicit integration wrapper so the scroll styles apply.
- Checked Report 4 in the explicit Vite demo on desktop and at 375 × 812.
  Lists, code and table formatted; raw HTML remained literal and no image/link
  elements existed. Desktop page/client widths were both 1265 px. Mobile
  page/client widths were both 360 px (375 px viewport including its scrollbar);
  code was 286/1289 px client/content and table 286/807 px. Both blocks had internal
  horizontal scrolling. Saved screenshots privately; restored the viewport.
- A cold development-worker load initially used the safe plaintext fallback;
  selecting the report again after loading rendered the Markdown. Kept the
  shared one-second parser budget unchanged.
- Fresh candidate and security verdicts: pending at this checkpoint.

## Resources and limits

Used configured roles: test_designer/baseline_verifier/candidate_verifier on
`gpt-6-luna` low; implementer on `gpt-6-luna` high; security_reviewer on
`gpt-6.1-sol` medium. Coordinator model/effort telemetry, per-agent input, cached
input, output, reasoning tokens, cost and attributable elapsed times were
unavailable. Did not infer savings. Reused the shared renderer rather than adding
another parser. Kept the log to the task evidence and material findings.

The local Docker engine was unavailable, so a local image build could not run.
CI's packaged-image check remains the image-build evidence. Browser preview uses
synthetic reports and identity, not a hosted application or new real-session test.
Cross-role Reporter API parity task 6.1 and final team acceptance are not claimed
by this Maintainer adoption record.
