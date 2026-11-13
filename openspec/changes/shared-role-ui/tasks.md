# Tasks

## 1. Shared role presentation

- [ ] 1.1 Establish and independently verify a focused UI baseline for role shell, session states and responsive presentation.
- [x] 1.2 Implement the shared shell and styling, integrate Developer and Maintainer, and provide a Reporter-compatible interface.
- [x] 1.3 Independently verify both frontend suites and production builds; review narrow viewport and keyboard behavior.
- [x] 1.4 Document Reporter integration and remaining real-service limitations.
- [x] 1.5 Consolidate Maintainer identity and sign-out into the workspace header; align Account navigation, surfaces and light/dark colors with the dashboard. Run Maintainer verification and check packaged Account resources and session controls. Presentation follow-up authorized without TDD. Evidence: `logs/maintainer/2026-11-13-account-ui.md`; visual browser inspection remains unavailable.

## 2. Shared sign-in presentation

- [x] 2.1 Extract shared sign-in presentation and apply it to Developer and Maintainer without changing authentication or session behavior.
- [x] 2.2 Verify both role frontend suites and document Reporter integration; do not claim a shared role-routing login until one exists.

## 3. Common root sign-in

- [ ] 3.1 Verify role-aware routing, inactive denial and no wrong-role queue fetch against a focused Red baseline.
- [ ] 3.2 Integrate the root entry with existing sessions and verify role routing, permission boundaries and responsive presentation.
- [x] 3.3 Restore Account header, identity and navigation after the profile merge; remove Developer and Reporter credential forms and return signed-out visitors to `/`. Preserve CSRF-protected logout and recoverable errors. Verify both role frontends and Account DOM/session controls. Owner requested direct implementation without TDD. Evidence: `logs/maintainer/2026-11-13-account-root-login.md`; browser inspection unavailable.
