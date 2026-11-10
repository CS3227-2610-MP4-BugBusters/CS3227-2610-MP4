# Frozen Maintainer reflection protocol

Recorded the protocol and budgets in protocol.json before the controlled runs.
Generate isolated coordination conditions with:

```sh
python workflow/evaluation/reflections/coordination.py --output /tmp/mp4-comparison-new
```

Use a fresh implementation agent for each directory. In multi conditions, hand the
candidate to a fresh read-only verifier. Keep the coordinator's baseline outside the
allowed agent directories, record dispatch/completion times, and compare frozen
acceptance checks and file hashes. Never carry fixes between conditions. The tasks
are intentionally small synthetic examples, not reused MP application code.

Run the controlled application reuse comparison from the authentication Gradle root:

```sh
./gradlew --no-daemon -I ../../../workflow/evaluation/reflections/reuse.gradle summaryReuseExperiment
```

Check the pinned wrapper before executing it, using
`python workflow/security/check.py --wrapper-only` from the repository root.
The Java harness reuses current MP4 test fixtures and the actual summary service;
it does not invoke SoC. See logs/experiments for counts, timings and limitations.
Prepared maintainer-examples.json for the future live context comparison; freeze the
supported model/quota/settings before collecting scores. Unavailable results remain
unavailable rather than safety-only passes. Keep provider caching and application
reuse measurements separate.
