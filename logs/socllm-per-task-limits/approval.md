# Approval: socllm-per-task-limits tasks 1.1, 2.1, 2.2

## Summary

The feature owner reviewed the Red baseline that the independent verifier
confirmed (PASS) and approved implementation, with per-task limits kept in code
(`SocLlmTaskPolicies.defaults()`), not `.env`. The approval covers tasks 1.1,
2.1 and 2.2 only. Tasks 3.1 and 3.2 stay blocked until PostgreSQL tools are
available. The owner accepted the API the tests assume and did not ask for the
verifier's non-blocking gaps to be covered first.

## Approved baseline

- Revision: HEAD `db24eb6b7015e3161626d42d95f5b597d90dc991` plus working-tree test files.
- `src/backend/auth/src/test/java/mp4/socllm/SocLlmTaskPolicyTest.java`
  `972545d18ccf55fe1f90c110e784ba7ff5975c4c59957901fc9e174b8fbc1ee3`
- `src/backend/auth/src/test/java/mp4/socllm/SocLlmRoleServiceTest.java`
  `1337ceee06d7ae01ad6bf21e5f4a1687b92cf21d316ba03680b88bdade069fe9`
- `src/backend/auth/src/test/java/mp4/socllm/MaintainerAdmissionAdapterTest.java`
  `812066166db0cdeefaff8fa7de54ad11eed2fd0a3e0ee2e4af9b14462333cb4f`
- Red evidence: [red.md](red.md). Baseline verifier: PASS. The Red reproduced as a
  compile failure naming only `SocLlmTaskPolicy` / `SocLlmTaskPolicies`, and the
  clean-tree setup control exited 0.
- Approved API: a 7-argument `SocLlmTaskPolicy` that throws `IllegalArgumentException`;
  `SocLlmTaskPolicies.defaults()`, `of(Map)` and `forTask(Task)`; the package-private
  `SocLlmRoleService(Role, RoleAdmission, ProviderCall, SocLlmTaskPolicies)`;
  `MaintainerAdmissionAdapter(RoleAdmission, SocLlmTaskPolicies)`.
- Non-blocking gaps accepted: no test that policies are immutable after `of(Map)`;
  the content-leak check covers input text only; the non-Maintainer reservation
  formula is assumed by TL-05.

## Re-approval after Java 17 test fix

### Summary

The implementer found that the approved `SocLlmTaskPolicyTest` used
`Duration.isPositive()` (Java 18+), but the build compiles with release 17. The
test designer replaced it on line 23 with `compareTo(Duration.ZERO) > 0`. A fresh
baseline verifier returned PASS: the change is limited to that one line, the
assertion meaning is unchanged, no other API newer than Java 17 appears, and Red
reproduced on clean HEAD with only the missing-type errors. The feature owner
approved implementation against this updated baseline. Scope, API and the
limits-in-code decision are unchanged. Tasks 3.1 and 3.2 remain blocked.

### Approved baseline (supersedes the hashes above)

- `SocLlmTaskPolicyTest.java` `1f7d509fdf5da45900974c90bf66a1dc03d9b8576476edc61ac4c2bc300291c0`
- `SocLlmRoleServiceTest.java` `1337ceee06d7ae01ad6bf21e5f4a1687b92cf21d316ba03680b88bdade069fe9`
- `MaintainerAdmissionAdapterTest.java` `812066166db0cdeefaff8fa7de54ad11eed2fd0a3e0ee2e4af9b14462333cb4f`
- Evidence: [red.md](red.md), section "Re-baseline 2".
