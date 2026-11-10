# AI provider cancellation and capacity

The local SoC adapter releases its single-request permit as soon as the HTTP
future is cancelled. A focused fake transport demonstrates that a replacement
can start while the fake's cleanup remains pending. This is a local test of a
controlled signal; Java HttpClient does not guarantee a subscriber terminal
callback after cancellation or expose a reliable remote cleanup completion.
The production cleanup guarantee remains unresolved.

## Baseline and coordination

- Starting HEAD `8b74eac4e7f2403fc21a6dc57efef41af4ad6761` on
  `codex/developer-notes-local`. Test file is uncommitted.
- Test designer received the AI OpenSpec, local adapter contract, provider and
  existing timeout tests. The initial fake did not expose a cleanup signal to
  the provider. It was corrected to deliver a terminal subscriber callback
  after a controlled latch and to require a typed BUSY response.
- An independent baseline verifier reviewed and reran the corrected test.
  `./gradlew test --tests mp4.platform.SocSuggestionProviderCancellationCapacityTest`
  from `src/backend/auth` failed: the replacement was admitted instead of
  returning BUSY. Cleanup recovery assertions were not reached.
- Corrected test SHA-256:
  `d07a71e0451c580348b593e178cc9ce86dcb0102269ce377673b700804635085`.
  The earlier test bytes do not establish this corrected baseline.
- Actual per-agent tokens and cost were unavailable. The additional test
  revision and independent review are coordination overhead; no savings are
  inferred.

## Investigation and unresolved decision

The implementer compared the new regression with the existing
`SocSuggestionProviderTimeoutLifecycleTest`: the new case requires BUSY
until a terminal cleanup signal, while the older fake expects immediate reuse
after cancellation without such a signal. The new test fails and the older
test passes against the unchanged provider.

Java 17 HttpClient does not promise a subscriber callback after cancellation.
Waiting for that callback could therefore leave the adapter permanently busy;
releasing on future cancellation cannot establish that transport cleanup has
finished. No production change was made and neither guarantee is claimed.
Resolving this needs a transport with a verifiable termination boundary or an
explicitly agreed availability/recovery policy. The failing test remains as
uncommitted evidence. The focused AI context-bound work is recorded separately
in `2026-11-10-ai-context-bound.md`.
