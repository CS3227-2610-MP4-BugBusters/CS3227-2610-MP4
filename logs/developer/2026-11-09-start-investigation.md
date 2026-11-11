# Start investigation

An assigned Developer can start an investigation from report details. The
server changes Assigned to In progress only when the session's Developer is
still assigned and the submitted report version matches. The update increments
the report version. The checklist keeps unsaved text and receives the new
version when its previous version matches the transition.

The Developer frontend verification passed (48 tests, typecheck and build).
The disposable PostgreSQL authentication suite passed (84 tests), including
the transition and authorization checks. The first integrated run revealed a
JSON request binding mismatch, which was corrected before the passing run.
One scoped worker implemented the slice under the user's simplified workflow;
per-agent token usage was unavailable. Changes are uncommitted. Resolution
proposal, lifecycle history and cross-role integration remain open.

## Context and interpretation for reflection

The user requested a lighter workflow. One scoped worker handled the status transition, UI and focused tests; the server checks current assignment and version before changing status.

The first integrated run exposed a JSON binding mismatch that component tests did not catch. The passing backend suite followed the correction; no independent cost comparison or per-agent token measurement was available.
