# Summary timeout interruption fixture correction

Fixed the timeout race test so it waits for the provider worker to observe its interrupt before checking the cancellation and held-lease assertions. The test still requires an AI timeout, retains the lease while the worker is blocked, confirms renewal, releases the worker only after those checks, and verifies the lease closes only when the worker ends. The focused test passed. The first invocation did not run because `JAVA_HOME` pointed to a missing JDK; rerunning with the installed Adoptium Java 21 passed. No production code or manifest was changed. Fresh original-stub verification and full candidate verification remain with the coordinator.

## Technical evidence

- Task: bounded MNT-P4 scheduling-oracle correction in `timedOutCallRetainsAdmissionLeaseUntilInterruptIgnoringWorkerActuallyEnds`.
- Starting revision: `5355a23ce0a3c9bf9fbb700aafb3be0bc4ebe76b`.
- Changed paths: `src/backend/auth/src/test/java/mp4/maintainer/summary/QueueSummaryTest.java` and this new log only. No app code or manifests changed.
- Change: added `interruptObserved` latch, decremented immediately after setting the interruption flag in the worker's `InterruptedException` handler. The caller waits up to one second for that signal after timeout, then checks interruption, lease held, lease renewed, and release latch still closed before its `finally` block allows the worker to finish.
- Failed setup attempt: `.\gradlew.bat --no-daemon test --tests mp4.maintainer.summary.QueueSummaryTest.timedOutCallRetainsAdmissionLeaseUntilInterruptIgnoringWorkerActuallyEnds` from `src/backend/auth`; Gradle exited before build because `JAVA_HOME=C:\Users\ngkok\AppData\Local\Temp\textmax-gradle-setup\jdk-25\jdk-25.0.4+7` did not exist.
- Passing check: set process `JAVA_HOME` to `C:\Program Files\Eclipse Adoptium\jdk-21.0.5.11-hotspot`, then ran the same focused Gradle test from `src/backend/auth` — BUILD SUCCESSFUL (5 tasks; test task passed).
- Test SHA-256: `A3F38FABCE0BA206E022B956D2E10B4903F5665EAE6A859799AB52069FB19F36`.
- Role/model: test_designer; configured model `gpt-6-luna`, reasoning effort `high`; actual runtime model and token usage are unavailable in the trace.
- Remaining checks: fresh original-stub Red rerun and candidate suite/review are pending with the coordinator; this role did not change the approved manifest.

## Independent fixture recheck

Confirmed the correction with a separate baseline verifier (gpt-6-luna/high).
Copied the exact test into the isolated original-stub checkout at `5355a23`;
both normalized hashes matched `a3f38fabce0ba206e022b956d2e10b4903f5665eae6a859799ab52069fb19f36`.
Reviewed the diff: only the timeout case changed, and all interruption, held
lease, renewal, worker blocking and eventual release assertions remain.
The focused timeout test compiled and failed as expected against the original
stub because no worker starts: one failure, zero errors or skips. The other
twenty tests were unchanged from their earlier original-stub verification.
Updated only this test's entry in the current post-merge input manifest; retained
the historical manifests and their original hashes. Candidate verification is
separate and remains pending.
