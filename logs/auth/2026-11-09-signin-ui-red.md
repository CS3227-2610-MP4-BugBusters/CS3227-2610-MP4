# Maintainer sign-in UI Red evidence

Five focused Maintainer sign-in tests cover unauthenticated form and protected-read gating, valid credential submission with duplicate prevention and trusted identity refresh, wrong-role denial with sign-out availability, safe recoverable login failure, and protected-content clearing plus login blocking during pending logout. The baseline fails because App has no sign-in form or sign-out action. Existing App regressions and TypeScript typecheck pass. Implementation and independent baseline review remain open. Unit tests do not establish real-session behavior or the three-role browser flow.

## Technical evidence

- Task: `add-shared-authentication` 4.2, Maintainer UI portion
- Starting revision: `24ce947c6f03e9a458776cdfb5430ffd69b02029`
- Test file: `src/frontend/maintainer/src/signin.test.tsx`
- Test SHA-256: `6A4C8AA6D697F5BACA1411CB149341084348196B0DD41E9A078715407B6E008D`
- Focused command: `npm.cmd test -- src/signin.test.tsx` (from `src/frontend/maintainer`)
- Focused result: **Red, 5 tests failed**. Failures are absent UI controls/actions in App; no module/import/setup failure.
- Regression command: `npm.cmd test -- src/App.test.tsx`
- Regression result: **Pass, 16 tests**
- Type command: `npm.cmd run typecheck`
- Type result: **Pass**
- Scope: only the assigned test file and this evidence record; no application code changed.


Before independent verification, added a development-only same-origin `/api` proxy
in Vite configuration (default loopback backend port 8080; optional MP4_AUTH_ORIGIN).
Preserve browser Host for explicit origin checks. This does not add runtime sign-in
logic or change Vitest settings. The companion normalized input manifest includes
the final proxy configuration; independent verification must rerun typecheck and
Red using that exact baseline. Browser/API integration remains separately verified.
