# Shared Markdown CI timing failure — 10 November 2026

The Node 22 shared Markdown job in GitHub Actions run 42842600390 failed its cancellation scaling test: the recorded larger-to-smaller time ratio was 24.98, exceeding the threshold of 20. The same run passed on Node 24. The failure was in a timing-sensitive test, not in a sign-in assertion.

The failed GitHub Actions job was rerun without code or test changes. Attempt 2 completed successfully and the PR check list reported all 14 checks passing. One successful rerun does not prove the timing test cannot fail intermittently; monitor it and investigate the benchmark if it repeats. No threshold was changed and no resource performance claim is made.
