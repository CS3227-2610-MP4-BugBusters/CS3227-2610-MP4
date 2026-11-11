# Red: socllm-per-task-limits 1.1, 2.1, 2.2

## Summary
New tests describe per-task SoCLaaS policies (defaults, validation), per-task call bounds in SocLlmRoleService, and the Maintainer reservation derived from its policy. None of the policy types exist yet, so test compilation fails. That is the expected Red. Postgres tests (TL-07..TL-10) are out of scope.

Assumed API (design.md names only SocLlmTaskPolicy, defaults(), constructor injection): SocLlmTaskPolicy(maxInputBytes, maxInstructionBytes, maxOutputTokens, maxDeadline, startsPerWindow, window, maxInFlightPerActor) with accessors of the same names; SocLlmTaskPolicies.defaults(), SocLlmTaskPolicies.of(Map<Task,SocLlmTaskPolicy>) (throws IllegalArgumentException if a task is missing), forTask(Task); SocLlmRoleService(Role, RoleAdmission, ProviderCall, SocLlmTaskPolicies); MaintainerAdmissionAdapter(RoleAdmission, SocLlmTaskPolicies); invalid policy throws IllegalArgumentException.

## Revision and hashes
HEAD db24eb6b7015e3161626d42d95f5b597d90dc991 plus uncommitted openspec change.
972545d18ccf55fe1f90c110e784ba7ff5975c4c59957901fc9e174b8fbc1ee3 *SocLlmTaskPolicyTest.java
1337ceee06d7ae01ad6bf21e5f4a1687b92cf21d316ba03680b88bdade069fe9 *SocLlmRoleServiceTest.java
812066166db0cdeefaff8fa7de54ad11eed2fd0a3e0ee2e4af9b14462333cb4f *MaintainerAdmissionAdapterTest.java

## Commands
- Setup control: cd src/backend/auth && ./gradlew.bat --no-daemon test --tests mp4.socllm.SocLlmHttpProviderTest -> exit 1 (fails at compileTestJava on the same new tests, since the whole test source set compiles together; not an environment fault). Output: C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/red/setup.txt
- Focused Red: ./gradlew.bat --no-daemon test --tests mp4.socllm.SocLlmTaskPolicyTest --tests mp4.socllm.SocLlmRoleServiceTest --tests mp4.socllm.MaintainerAdmissionAdapterTest -> exit 1. Output: C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/red/red.txt
- Failures: compileTestJava errors, all "cannot find symbol" for SocLlmTaskPolicy / SocLlmTaskPolicies (no unrelated errors).
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\SocLlmRoleServiceTest.java:38: error: cannot find symbol
    private static SocLlmTaskPolicies policies(SocLlmTaskPolicy reporter, SocLlmTaskPolicy developer) {
--
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\SocLlmRoleServiceTest.java:38: error: cannot find symbol
    private static SocLlmTaskPolicies policies(SocLlmTaskPolicy reporter, SocLlmTaskPolicy developer) {
--
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\SocLlmRoleServiceTest.java:38: error: cannot find symbol
    private static SocLlmTaskPolicies policies(SocLlmTaskPolicy reporter, SocLlmTaskPolicy developer) {
--
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\SocLlmRoleServiceTest.java:46: error: cannot find symbol
    private static SocLlmTaskPolicy small() {
--
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\SocLlmRoleServiceTest.java:50: error: cannot find symbol
    private static SocLlmTaskPolicy big() {
--
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\SocLlmTaskPolicyTest.java:12: error: cannot find symbol
    private static SocLlmTaskPolicy policy(int in, int instr, int out, Duration deadline, int starts, Duration window, int inFlight) {
--
D:\cs3227\CS3227-2610-MP4\src\backend\auth\src\test\java\mp4\socllm\MaintainerAdmissionAdapterTest.java:28: error: cannot find symbol
        Map<Task, SocLlmTaskPolicy> map = new EnumMap<>(Task.class);

## Re-baseline 2
Plain-language summary: When the policy types existed, the approved SocLlmTaskPolicyTest did not compile because it called Duration.isPositive(), which needs Java 18 while the module builds for Java 17. The test now checks positivity with compareTo(Duration.ZERO) > 0, which means the same thing. No other assertion changed. The other two test files were scanned and use only Java 17 APIs; they are unchanged. Re-running on the original code (clean HEAD worktree, no implementation) still fails only because SocLlmTaskPolicy and SocLlmTaskPolicies do not exist.

Diff of the fix (SocLlmTaskPolicyTest.java line 23):
- `&& p.maxDeadline().isPositive() && p.window().isPositive(), task.name());`
+ `&& p.maxDeadline().compareTo(Duration.ZERO) > 0 && p.window().compareTo(Duration.ZERO) > 0, task.name());`

New SHA-256 (real tree == worktree copies):
1f7d509fdf5da45900974c90bf66a1dc03d9b8576476edc61ac4c2bc300291c0 *SocLlmTaskPolicyTest.java
1337ceee06d7ae01ad6bf21e5f4a1687b92cf21d316ba03680b88bdade069fe9 *SocLlmRoleServiceTest.java (unchanged)
812066166db0cdeefaff8fa7de54ad11eed2fd0a3e0ee2e4af9b14462333cb4f *MaintainerAdmissionAdapterTest.java (unchanged)

Command (in clean-head/src/backend/auth): ./gradlew.bat --no-daemon test --tests mp4.socllm.SocLlmTaskPolicyTest --tests mp4.socllm.SocLlmRoleServiceTest --tests mp4.socllm.MaintainerAdmissionAdapterTest -> exit 1 at compileTestJava; errors are "cannot find symbol" for SocLlmTaskPolicy / SocLlmTaskPolicies only.
Output: C:/Users/tieut/AppData/Local/Temp/claude/df5390b674023e49/7fa0d685-24c5-4c90-ab02-274f443beffe/scratchpad/red2/red.txt
