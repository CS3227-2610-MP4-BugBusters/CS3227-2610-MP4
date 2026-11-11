# Shared role UI: verification evidence

## Summary

The Developer and Maintainer apps need a consistent, accessible role workspace. Before implementation, focused tests independently reproduced missing role-labelled navigation in both apps. Existing signed-out/denied protections and existing report controls passed. This baseline does not verify a narrow viewport or visual appearance; those remain candidate checks.

## Baseline details

- Starting HEAD `a51ab63b2de968097535481ff9d1b59d411119f8`; existing uncommitted work preserved.
- Developer: `npm test -- --run src/SharedRoleWorkspace.test.tsx`: 3 tests, one expected navigation failure; test SHA-256 `01be159722162fa46654f0e389ed2f129e87877f9d3ea6119573ee7f3a53469e`.
- Maintainer: `npm test -- src/SharedRoleWorkspace.test.tsx`: 4 tests, one expected navigation failure; test SHA-256 `f40137545a98ea4ff1c93c5e6f05c9c5ac0989cf3f580ce84b3c489ffa20cbc1`.
- The test designer and fresh baseline verifier reproduced both failures. Configured model: gpt-6-luna, low reasoning; token usage unavailable.

## Access-loss baseline

An independent review found that a previously loaded Developer checklist remained visible after an authorization denial. A focused test and independent rerun reproduced this for Save/401 and Share/403 FORBIDDEN. Successful Save and a CSRF_INVALID 403 both passed; the latter preserved unsaved input and sent the CSRF header.

- Command from the Developer frontend: `npm test -- SharedRoleAccessLoss.test.tsx --reporter=dot`.
- Result: 2 expected behavior failures and 2 passing controls, using mocked HTTP responses.
- Frozen test SHA-256: `b3c8db0b6fabaf1d445f7ddbd3feb79810c009f74d1a911ffe66dce79223b642`; starting HEAD `a51ab63b2de968097535481ff9d1b59d411119f8` with existing local changes.
- The tester and independent verifier used the configured gpt-6-luna low role. Token usage unavailable; server-side authorization and CSRF middleware were not exercised by this frontend test.

## Dashboard session-loss baseline

A separate test verified that an assigned-report list request returning 401 currently leaves the Developer identity and navigation visible and shows a generic error. The success control passed. A Maintainer test for a protected query losing its session already passed.

- Developer command: `npm test -- SharedRoleSessionLoss.test.tsx --reporter=dot`; 1 success control passed, 1 session-loss case failed with all four relevant observations collected.
- Independent verifier reproduced the result. Frozen Developer test SHA-256: `a46a0651bdf6aeea0ec7342b058bf74ce24ebc8a84b49865024c8e2d1f2ac144`.
- Maintainer session-loss test passed 2/2 as regression coverage; it was not a Red. These tests use mocked client responses, not live server sessions.

## Inactive-account baseline

The test designer and independent verifier reproduced a case where an inactive Developer account receives FORBIDDEN on a protected checklist request, but another report title and the signed-in shell remain visible. The first test fixture incorrectly returned the denial from the public CSRF endpoint; it was corrected to return a token first and deny the protected draft request, matching the backend filter.

- Command: `npm test -- SharedRoleInactiveAccount.test.tsx --reporter=dot` from the Developer frontend.
- Result: 1 expected failing test with 5 collected assertion failures. The selected report and draft were cleared, but the second report and identity/navigation remained.
- Corrected test SHA-256: `c4f7879e804e32d57690846d5d3d007d2501da5cd31af67da63f5d2c44e76a21`.
- Test uses mocked client responses; live account deactivation remains unverified. Agent token usage unavailable.

## Final candidate

The shared workspace is integrated into the Developer and Maintainer frontends. It supplies a responsive header, role navigation, skip link and shared styling; the Developer list has accessible report rows. The Reporter owner can integrate the same shell, but no Reporter app or Reporter service was added. A denied or expired Developer session clears the protected workspace; a CSRF error preserves unsaved input.

- Independent candidate verification: Developer `npm run verify` passed typecheck, 11 files / 36 tests and production build; Maintainer `npm run verify` passed typecheck, 7 files / 46 tests and production build. Frozen test hashes were verified.
- Independent security review found no confirmed blocker in the candidate. Tests do not cover every possible late-denial path; the current server's logout/CSRF contracts limit an additional defensive edge.
- Manual browser review used synthetic local data. Maintainer desktop and a 390px viewport were inspected; a keyboard check at 390px focused the visible `Skip to main content` link on the first Tab, and measured document width 375px within the 390px viewport. Developer desktop was inspected with a local synthetic API; at a 390px viewport its document measured 390px, two report rows rendered, and first Tab focused the visible skip link.
- Docker was unavailable locally. Both Docker frontend stages were reviewed for copying the shared directory, and both local production builds passed; a container build remains unverified.
- No Reporter app, real Reporter service or deployed environment was tested. Agent token usage was unavailable.

## Context and interpretation for reflection

The UI work used focused role-workspace and access-loss tests, then candidate and security review. Synthetic browser data was used for the recorded visual and keyboard checks.

The separate access-loss fixtures demonstrate why denied sessions and CSRF failures need different UI treatment. The visual checks do not prove a complete real-session browser journey.
