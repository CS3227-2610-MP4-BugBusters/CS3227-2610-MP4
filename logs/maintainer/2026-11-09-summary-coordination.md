# Summary coordination findings

Used separate test-design, baseline-verification, implementation, candidate and
security-review roles for the Maintainer summary. Kept UI work and backend work
in separate files. Serialized Gradle runs in the primary checkout; used a second
checkout to verify corrected tests against the original stub.

Found a limitation in the initial Red review: every test failed at the stub, so
execution alone did not expose contradictions in assertions reached only after
implementation. Three backend oracles needed correction to follow the unchanged
spec: oldest-first context selection, explicit update truncation, and a matching
filtered snapshot. A later full run exposed an asynchronous test assumption;
replaced an immediate interruption-flag check with a bounded worker signal while
retaining the cancellation and held-lease requirements. Reverified the corrected
tests against the original stub rather than changing the implementation to fit.

The UI reviewer also required explicit assertions for excluded IDs and actual
Closed queue rows. Retained Closed browsing while disabling summary generation
for that filter. These changes improved the oracles; they do not prove provider
grounding or real authorization.

Independent security review found gaps beyond the initial tests: access could
change during the final snapshot/cache operation, cached findings bypassed byte
limits, and a cache entry could expire during its delivery recheck. Added focused
regressions and routed repairs back to the implementer. The final static review
passed for the bounded domain; runtime candidate verification is recorded
separately in the summary implementation evidence.

## Resource and process limits

Used gpt-6-luna/high for test design, baseline review and implementation;
gpt-6-luna/low for the candidate role; and gpt-6.1-sol/medium for security review.
Configured roles and actual dispatch settings are recorded in the related logs.
Token usage, reasoning-token fractions, cache-hit measurements and monetary cost
were unavailable. Have not run a controlled single-agent comparison and cannot
attribute a measured cost or quality advantage to delegation.

The extra correction and verification rounds consumed work without adding product
scope. For the next test handoff, will check fixture ordering, filter membership,
Unicode/byte units and synchronization explicitly, including assertions hidden
behind the first expected failure. This is an observed workflow improvement,
not evidence that the workflow mechanically prevents all test defects.
