# Design

## Context

Inspected the Developer JDBC stores, JDK `HttpExchange` handlers and synthetic-session
harnesses, the shared reports migration, CI and the Maintainer `parseUser` consumer.
No production application/auth scaffold exists. Java 17 is the existing compilation
target (local Java 21); Maven is absent from the current shell. Keep existing role
tests and contracts intact. See proposal.md for ownership and motivation.

Status: proposed implementation decisions; no authenticated endpoint, migration,
provisioned account, security test result or deployment is claimed.

## Goals / Non-Goals

Deliver one real identity boundary for all roles and a narrow integration path.
Keep account creation operator-only: no public signup, role editor, password-reset
email, SSO or user-management dashboard in this increment. Do not take over the
Developer-owned shared shell/database tooling or implement report lifecycle rules.

## Decisions

### Library and integration seam

Propose Spring Boot with Spring Security's servlet support, Spring Session JDBC,
JDBC account access and PostgreSQL. Use the Boot-managed compatible dependency set,
pin an exact supported version and Maven wrapper/checksums in the runner task, and
compile for Java 17. Choose established session/CSRF/password infrastructure over
custom cryptography or browser-stored bearer tokens. The official Boot requirements
support Java 17; the library's session and CSRF rules are integration obligations,
not proof that defaults alone satisfy this spec.

Keep the application entry and security adapters under `src/backend/platform/`, auth
services/tests under `src/backend/auth/`. Coordinate the application/routing adapter
with the Developer owner. Existing `HttpExchange` handlers cannot be directly mounted
as servlet controllers. Prefer a thin servlet adapter around existing feature
services/stores, preserving response contracts and existing tests; a separate
integration test must prove this route invokes the same store/authorization. Do not
replace a handler with a reference fake or silently port its behaviour. Restrict
cross-owner edits to the agreed adapter boundary before implementation.

### Accounts and provisioning

Propose auth-owned `app_accounts` with UUID id, unique normalized username,
display_name, encoded password, fixed role, active and auth_generation. Register an
additive auth migration through the Developer-owned migration tooling; do not edit
`001_create_reports.sql`, reset shared data or add foreign keys without its owner.
A database constraint permits exactly REPORTER/DEVELOPER/MAINTAINER. Role is immutable
through this application's operations. Coordinate later account foreign keys with
the schema owner; this change does not prove report/proposal referential integrity.

The team-operated command creates, disables or resets accounts, connecting through
environment-provided database configuration. Read the password from a console prompt
or standard input; never accept it as a command argument or print it. Provision
explicit UUIDs for synthetic fixtures; generate UUIDs for ordinary creation. Duplicate
usernames fail unchanged. No default production accounts or startup password seeds.
Use Spring Security delegating encoders with BCrypt cost 12 for new hashes. Provision
passwords of 12-72 UTF-8 bytes, reject overlong values rather than truncate, never
trim passwords. Login accepts 1-72 bytes and compares through the encoder; unknown
users perform a dummy hash comparison. Proposed operator policy can be reviewed
without altering account identity or exposing passwords.

Normalize usernames by trimming and ASCII lowercasing, then accept 3-64 characters
`[a-z0-9._-]`; display names are nonblank, at most 200 UTF-16 code units (matching the
existing Maintainer DTO parser) and contain no control characters. Validate this
unit explicitly in Java; do not substitute a Unicode code-point count. Bound all auth request bodies to 8 KiB. Strictly reject unknown,
repeated or malformed fields. Disable/reset increments auth_generation atomically;
all authenticated requests compare the captured generation with the current account.
Missing/deleted/reset-generation identities return 401; inactive accounts return 403.
Unknown, inactive and incorrect-password login attempts all return the same generic
401 envelope. Store failures return safe 503 with no authentication fallback.

### HTTP contract

All endpoints are same-origin; JSON errors are `{code,message,requestId}` with a
safe generated request ID and no framework redirects, stack traces or secrets.
Reject caller-supplied actor/role selectors. Public means usable before login,
not exempt from validation/rate/CSRF controls.

| Endpoint | Input | Result |
| --- | --- | --- |
| GET /api/auth/csrf | No selectors/body | 200 `{token,headerName:"X-CSRF-TOKEN"}` bound to the current pre-login/auth session; no-store |
| POST /api/auth/login | form-urlencoded exact username/password plus X-CSRF-TOKEN header | 200 `{id,displayName,role,active:true}`, rotated session ID; generic 401 UNAUTHENTICATED on credential failure |
| GET /api/auth/me | Session cookie only | 200 same safe user shape; 401 UNAUTHENTICATED if absent/expired/revoked, 403 FORBIDDEN if inactive |
| POST /api/auth/logout | Empty body, session cookie and CSRF header | 204, invalidate session and expire cookie; valid anonymous CSRF session may also log out |

`GET /api/auth/csrf` is the only anonymous session/bootstrap response. Login/logout
clear the old CSRF token; fetch a new one afterwards. The token response may be held
in browser memory but never logged or persisted in localStorage. Protected endpoints
check authentication first, then role and feature authorization; missing/invalid
CSRF is 403 CSRF_INVALID for unsafe requests, including anonymous login. Authenticated
wrong-role requests are 403 FORBIDDEN. Auth payload validation is 400 VALIDATION_FAILED;
unsupported methods are 405 with Allow. Disable default form-login redirects,
HTTP Basic, public signup, remember-me, URL session tracking and synthetic bearer auth.

### Sessions, shared limits and concurrency

Use server-side JDBC sessions so replicas/restarts do not bypass revocation or
rate state. Set a proposed 30-minute idle timeout and eight-hour absolute lifetime,
checked server-side using a testable clock. Rotate the pre-login ID on success and
reject its replay. Production cookie `MP4SESSION` is host-only Path=/, Secure,
HttpOnly, SameSite=Lax; permit non-Secure only for explicit loopback development.
Trust forwarded scheme/client identity only from the configured deployment proxy;
never trust arbitrary caller X-Forwarded-* headers. Reject unsafe cross-origin
requests and enable no credentialed cross-origin CORS. Use HTTPS in production.

Propose a shared PostgreSQL rolling 15-minute login-attempt budget: 10 admitted
credential checks per normalized username, 100 per trusted client address. Count
valid-format attempts before password work, for existing and nonexistent names;
reject excess with 429 AUTH_RATE_LIMITED and bounded Retry-After. Atomic admission
must prevent two replicas exceeding either counter; no permanent lockout. Store
HMAC digests of limiter keys with an environment secret, expire records after the
window, cap retention at 24 hours and verify cleanup. Invalid syntax and CSRF failures
must not consume account budgets; proxy/global request-size controls still apply.
These are proposed application policies, not provider limits. Fail closed with 503
if shared admission/session/account state is unavailable. Never reuse this limiter
for the separate SoC gateway quota policy.

Read fresh active role/generation before protected work and asynchronous delivery.
A request already authorized before disablement can race with a domain transaction:
mutating services must participate in the agreed account/report lock protocol. Do
not claim session checking alone serializes deactivation with every report write.
Coordinate this with the schema/lifecycle owner and keep full-domain race acceptance
separate from AUTH-02's subsequent-request guarantee.

### Trusted actor and consumer contracts

Expose an immutable internal `{id:UUID,role,active,authGeneration}` resolved from the
session/account store; do not parse it from headers/body/query. Adapt it explicitly
to `ChecklistDraftStore.Actor` at the existing Developer boundary. Role services
retain assignment/ownership and current state/version checks. The public user DTO
matches Maintainer `parseUser` exactly; never expose authGeneration or password hash.
For the first real-session test use the existing assigned-list store with persisted
accounts/reports, not a protected placeholder endpoint. Coordinate routing with its
owner; preserve standalone synthetic harnesses as regression evidence. The shared
frontend owner integrates login/logout screens and current-user routing from these
contracts; the auth owner supplies endpoints, fixtures and access instructions.

## Risks / Trade-offs

- Servlet versus existing JDK handlers -> review the adapter boundary before code;
  require real-session HTTP integration, preserve existing tests and avoid a parallel
  production report store.
- JDBC migrations need shared tooling -> register auth-specific additive files with
  its owner; an absent migration runner is BLOCKED setup, never Red behaviour evidence.
- Token/session handling around login -> test rotation, token refresh, old-token
  rejection and browser cookie flags using the real security filter chain.
- Shared attempt counters can enable temporary denial of a guessed username -> keep
  windows bounded, avoid permanent lockout and test recovery without account mutation.
- Private data in logs -> log only safe outcome/request IDs; test password/session/
  CSRF canaries absent from bodies, assets and published evidence.

## Migration Plan

First publish the contract and establish a pinned runner with disposable PostgreSQL
and a passing setup control. Then take one bounded AUTH acceptance slice through
Red designer, fresh baseline verifier, owner approval, implementer, fresh candidate
verifier and separate security reviewer. Never count a missing import/tool/database
as Red. Keep approved input hashes through repair. Integrate real role requests,
then browser login/logout; all three accounts must work before declaring the team
unblocked. Use Reporter-owner human review for auth; agent PASS is not that review.

Deploy through the Maintainer-owned environment work in `complete-maintainer-role`
6.2 with Developer-owner review. Apply additive migrations before app startup. On
failure stop rollout and revert application code only if schema compatibility holds;
never delete accounts/sessions/report data to force rollback. Revoke sessions/reset
affected credentials separately when required. No deployment or owner agreement is
claimed by this proposal.

## References

Checked official documentation for the library proposal; pin the exact version and
recheck version-specific guidance when implementing:

- [Boot system requirements](https://docs.spring.io/spring-boot/system-requirements.html)
- [Spring Security CSRF and SPA integration](https://docs.spring.io/spring-security/reference/servlet/exploits/csrf.html)
- [Session management](https://docs.spring.io/spring-security/reference/7.0/servlet/authentication/session-management.html)
- [Password storage](https://docs.spring.io/spring-security/reference/features/authentication/password-storage.html)
