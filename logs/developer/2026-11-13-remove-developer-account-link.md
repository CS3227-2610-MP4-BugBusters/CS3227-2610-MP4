# Remove the broken Developer Account link

## Summary

Removed the Account link from the Developer sidebar because it led to an unloaded page. Dashboard and sign-out remain available; other roles retain the shared shell's existing default. A focused UI check first reproduced the unwanted link and then passed as part of Developer verification. Developer and Maintainer frontend verification passed. No independent review or live browser check was performed.

## Scope and coordination

- Starting revision: `1b2d3b7f5a787fe2c73d4bbc79a54f6f9e016a5c` on `main`; the working tree already contained the separate shared-investigation-notes implementation, docs, tests and logs, plus unrelated untracked OpenSpec and tool files. Those changes were preserved.
- User-provided screenshot identified the Developer sidebar and the requested removal. Read `openspec/changes/shared-role-ui/{design.md,tasks.md,specs/role-workspace-ui/spec.md}`, the Maintainer frontend baseline, `workflow/workflow-selection.md`, and the shared Developer checklist storage contract. This was a lightweight, presentation-only navigation change; it does not alter authorization, account data, shared report state or the other roles' default link.
- The coordinator performed the focused test, edit and checks without delegating to specialists; no independent review is claimed. The shared shell received an opt-out and the Developer workspace sets it. The existing session and sign-out controls were retained.

## Findings and fixes

- The shared role workspace inserted `/account/` unconditionally for authenticated roles. Before the change, the focused Developer workspace test failed because the Account link existed; Dashboard was expected to remain available.
- Made the Account link optional with its existing behavior as the default, and disabled it for Developer. The focused assertion and the complete Developer suite passed afterward.
- The first attempt to read the Maintainer baseline used a repository-root path from the Maintainer working directory and failed before running verification. Corrected the relative path, read the baseline and ran the Maintainer checks successfully. No application defect was inferred from the path error.

## Verification

- From `src/frontend/developer`: `npm test -- --run src/SharedRoleWorkspace.test.tsx` before the fix: **1 failed, 2 passed**, with the expected Account-link assertion failing.
- From `src/frontend/developer`: `npm run verify`: **68 tests across 14 files passed**, typecheck and build passed. Test runner duration 2.27 seconds.
- From `src/frontend/maintainer`: `npm run verify`: **116 tests across 18 files passed**, typecheck and build passed. Test runner duration 3.94 seconds. The default Account-link behavior is retained for other shell consumers.
- `git diff --check` passed. Checks used mocked frontend sessions and existing dependencies; no live browser, server or deployed revision was checked. Revision tested was the starting HEAD plus the existing shared-notes work and this uncommitted change.

## Reflection and resource measurements

The lightweight path avoided a specialist handoff for a small reversible UI change while retaining a behavioral regression and cross-consumer frontend checks. This is not a controlled efficiency comparison. Test start times shown by Vitest were approximately 09:04 local time on 13 November 2026; the shell clock at review was 09:05:11 +0800. Exact task elapsed time and coordinator model/effort were not exposed. Per-agent/coordinator input, cached input, output, reasoning and total tokens, as well as cost, were unavailable in the exposed tooling; they are not zero and no token savings are inferred. No sanitized trace location was exposed.

## Integration review

This change was separated onto `0271d05` for review. Developer `npm run verify` passed 63 tests, typecheck and build. Current main includes the Account page, while the Developer Vite server does not proxy `/account/`; removing the link also removes the obvious route to password changes in the packaged application. This draft requires a product decision and a browser check before merging. Token usage for this integration step was unavailable.
