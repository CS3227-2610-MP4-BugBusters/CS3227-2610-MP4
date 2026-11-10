# Shared role workspace

`RoleWorkspace.ts` and `RoleSignIn.ts` provide a common application frame and
sign-in presentation for the separately built role frontends. They accept the
framework's element factory, so each app can reuse the shared presentation
without adding a dependency from this directory to one app's `node_modules`.

`RoleSignIn.ts` renders controlled username and password fields, the submit
button, and optional status, notice, or error content. The consuming app owns
credential state, event handlers, busy state, and authentication. The shared
presentation does not choose a role, call an API, redirect, or authorize a
user.

The shell accepts a role label, optional signed-in identity, an authenticated
flag, navigation entries, optional account actions, and the role's own content.
Navigation and identity are rendered only when `authenticated` is true. Each
navigation entry must point to a real route or section in the consuming app.
The shell does not create report data, API calls, or role actions.

Import `role-workspace.css` from the role app's stylesheet. When building the
frontends in Docker, copy this directory beside both app directories so their
relative imports resolve in the same way as local builds.

Reporter can use the same interfaces when its frontend and authorized services
are available. This directory does not implement a Reporter screen or imply
that Reporter integration has been tested.

## Connecting a Reporter frontend

1. Place the Reporter application beside `shared`, import `RoleWorkspace.ts`
   and import `../../shared/role-workspace.css` from `reporter/src/style.css`
   (adjust the relative paths if the files differ). Ensure the build environment
   copies the shared directory alongside the Reporter package.
2. Check the signed-in account with the shared authentication service before
   setting `authenticated` to true. Pass `role: "Reporter"`, the confirmed
   display name, and navigation entries that point to real Reporter routes or
   sections. Supply the Reporter's own sign-out action as `accountActions`.
3. Render protected Reporter content in `children` only after that check. The
   shell hides navigation and identity when `authenticated` is false, but it
   does not authorize or automatically hide its children. The server must still
   authorize every report and checklist request.
4. On sign-out, session expiry or access denial, clear protected Reporter data
   and invalidate pending requests. Keep Reporter-owned report fields, API
   endpoints and checklist-response rules aligned with the Reporter contract.

The existing Developer and Maintainer imports demonstrate the React element
factory adapter. Verify the Reporter app's own build, access states and mobile
layout after integration; no Reporter application or real-service integration
was exercised in this change.
