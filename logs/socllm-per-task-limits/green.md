# Green: socllm-per-task-limits 1.1, 2.1, 2.2

## Summary

Goal: each SoCLaaS task now has its own limit policy. The policy sets input bytes,
instruction bytes, output tokens, deadline, starts per window, window length and
in-flight calls. All checks read from these policies instead of constants spread
across classes.

Changes:
- New `SocLlmTaskPolicy` record. Its constructor rejects zero, negative or null
  values, deadlines over 30 s, windows over 24 h, and byte or token limits above the
  transport ceilings.
- New immutable `SocLlmTaskPolicies` registry. `defaults()` gives every task the
  initial values. `of(Map)` makes a defensive copy and fails if any task is missing.
- `SocLlmRoleService` checks each call against the policy of the call's own task
  before admission or any provider work. Rejections stay `AI_UNAVAILABLE`, with no
  content in the error.
- `MaintainerAdmissionAdapter` reserves the Maintainer policy's
  instruction + input + output limits (34,816 by default).
- `platform/SocLlmConfiguration` reads the report bound from the Maintainer
  policy instead of the literal 24,576.
- The existing public constructors pass in `SocLlmTaskPolicies.defaults()`, so Spring
  wiring is unchanged.

Checks and results: the focused tests pass (13/13). The regression set passes
(23/23). A forced recompile of the whole test source set, including
`PostgresSocLlmAdmissionTest`, succeeds.

Review findings and fixes: the first approved baseline used `Duration.isPositive()`
(Java 18+) on test line 23, but the build compiles with release 17. The implementer
reported it as a baseline defect. The test designer fixed the line and the feature
owner re-approved (approval.md, "Re-approval after Java 17 test fix"). The main code
had the same issue at first and now uses `isNegative()`/`isZero()`.

Remaining limitations:
- Tasks 3.1 and 3.2 are still blocked: `PostgresSocLlmAdmission` still uses its fixed
  5-minute window, 3 starts and 1 in-flight call, so the policy values
  `startsPerWindow`, `window` and `maxInFlightPerActor` are not enforced yet. No
  PostgreSQL tests were run.
- The transport ceilings are duplicated as constants in `SocLlmTaskPolicy` and
  `SocLlmHttpProvider` (the provider is out of scope).
- The revision tested is `main` at `d9a04eb` plus the working tree. The approval
  names `db24eb6` (a `reporter_comment` commit). Under `src/backend/socllm` and
  `src/backend/maintainer` the two differ only in one line of
  `JdbcMaintainerRepository`. They also differ in `auth/build.gradle` source
  includes (discussion/reporter) and `AuthConfiguration` reporter routes. None of
  these touch the code under test.

## Revision

- `git rev-parse HEAD`: `d9a04ebf4a2e7e6a52de4ef60e1b848fc95c7b6b`, plus uncommitted working tree.
- Approved baseline reference: approval.md, "Re-approval after Java 17 test fix".

## Commands (from `src/backend/auth`)

1. Focused: `./gradlew.bat --no-daemon test --tests mp4.socllm.SocLlmTaskPolicyTest --tests mp4.socllm.SocLlmRoleServiceTest --tests mp4.socllm.MaintainerAdmissionAdapterTest` -> exit 0
   - SocLlmTaskPolicyTest 5 tests, 0 failures, 0 errors, 0 skipped
   - SocLlmRoleServiceTest 6 tests, 0 failures, 0 errors, 0 skipped
   - MaintainerAdmissionAdapterTest 2 tests, 0 failures, 0 errors, 0 skipped
2. Regression: `./gradlew.bat --no-daemon test --tests mp4.platform.MaintainerSocLlmConfigurationTest --tests mp4.platform.SocLlmRoleConfigurationTest --tests mp4.socllm.SocLlmHttpProviderTest --tests mp4.socllm.SocLlmRoleServiceTest --tests mp4.socllm.SocLlmTaskPolicyTest --tests mp4.socllm.MaintainerAdmissionAdapterTest` -> exit 0
   - MaintainerSocLlmConfigurationTest 3/3
   - SocLlmRoleConfigurationTest 2/2
   - SocLlmHttpProviderTest 5/5
   - SocLlmRoleServiceTest 6/6
   - SocLlmTaskPolicyTest 5/5
   - MaintainerAdmissionAdapterTest 2/2
   - Total 23 passed, 0 failed, 0 errors, 0 skipped
3. Compile-only: `./gradlew.bat --no-daemon compileTestJava --rerun` -> exit 0. This recompiles the
   whole test source set, including `PostgresSocLlmAdmissionTest`.

Full output: `C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/green/`
(`focused-3.txt`, `regression.txt`, `compile-test.txt`; earlier failed runs are `focused.txt` and `focused-2.txt`).

## Changed main files (SHA-256)

```
43c4e67194cee35d339775805e81c2fd3c324a9cb07dc95ef2ebaf1bd33ca1bd  src/backend/socllm/src/main/java/mp4/socllm/SocLlmTaskPolicy.java (new)
f23fc9dfc569be3270573242689a4f9b69c37f2bdd11a0487c2d61653f5476d6  src/backend/socllm/src/main/java/mp4/socllm/SocLlmTaskPolicies.java (new)
4712814a6502c9878b374f7f256137c18fdfcc15053988d5b526f294d45b39a6  src/backend/socllm/src/main/java/mp4/socllm/SocLlmRoleService.java
ef23c34cb43e7b6fc31252677295251d898d875dcf075a5a41113a9541e31ce7  src/backend/socllm/src/main/java/mp4/socllm/MaintainerAdmissionAdapter.java
87bdde6d445f6908bd5f46cde22a8a4a882f569389dc4f6e6b5352c90e45f8fd  src/backend/platform/src/main/java/mp4/platform/SocLlmConfiguration.java
```

## Approved test hashes (unchanged; match the re-approval)

```
1f7d509fdf5da45900974c90bf66a1dc03d9b8576476edc61ac4c2bc300291c0  src/backend/auth/src/test/java/mp4/socllm/SocLlmTaskPolicyTest.java
1337ceee06d7ae01ad6bf21e5f4a1687b92cf21d316ba03680b88bdade069fe9  src/backend/auth/src/test/java/mp4/socllm/SocLlmRoleServiceTest.java
812066166db0cdeefaff8fa7de54ad11eed2fd0a3e0ee2e4af9b14462333cb4f  src/backend/auth/src/test/java/mp4/socllm/MaintainerAdmissionAdapterTest.java
```

## Agent

- Role: implementer (`.claude/agents` implementer packet). No added tests beyond the baseline.
