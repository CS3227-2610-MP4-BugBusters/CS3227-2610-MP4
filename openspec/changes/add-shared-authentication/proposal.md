# Proposal

## Why

Need real shared identity and fixed-role accounts to unblock all role backends.
Corrected the earlier plan that treated authentication as an external dependency:
own this foundation as Maintainer, with Reporter-owner review, under the active
section-2 ownership table and the feature owner's confirmation.

## What Changes

- Provide controlled account provisioning with exactly one fixed role per account.
- Provide login/logout, session lifecycle, CSRF and a safe current-user contract.
- Resolve active identity on the server for every protected request; retain
  role/report authorization in each feature service.
- Integrate one real-session Developer read and the existing Maintainer identity
  consumer, coordinating the Developer-owned shared routing/API boundary.
- Add reproducible security, persistence and browser verification, with scoped
  test-design, baseline verification, implementation and independent reviews.

## Capabilities

### New Capabilities

- `account-provisioning`: controlled fixed-role accounts without public signup or self-promotion.
- `session-authentication`: login/logout, trusted identity, CSRF, session expiry and fail-closed access.

### Modified Capabilities

None. The existing role browser remains an isolated implemented baseline until
real adapters pass their own integration acceptance criteria.

## Impact

Plan new auth implementation/tests under `src/backend/auth/` and an application
adapter under `src/backend/platform/`. Coordinate shared API/routing and migration
registration with their Developer owner; do not rewrite their handlers or report
schema in this change. Existing JDK HttpExchange handlers and synthetic harnesses
are not production authentication. A servlet application adapter requires explicit
integration checks; classpath compilation alone cannot establish compatibility.
Keep role actor IDs as UUIDs and safe errors as `{code,message,requestId}`.

No implementation or task completion is claimed by these planning artifacts.
