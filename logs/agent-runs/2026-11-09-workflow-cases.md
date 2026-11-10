# Development workflow cases

Ran the team-plan workflow fixtures and retained failures instead of claiming
success. A verifier found the seeded priority defect, the coordinator repaired only
app.py, and the verifier rechecked it successfully in one repair cycle.

| Case | Observed result |
| --- | --- |
| DEV-WF-01 | Implementation handoffs report SPEC/app reads before edits; coordinator audit confirmed app.py-only changes and unchanged tests. Ordering is based on the agent's trace summary, not an exported full tool trace. |
| DEV-WF-02 | Required check exited 1 on PRIORITY-01; verifier reported FAIL and made no repair or completion claim. |
| DEV-WF-03 | Unrelated-file fixture passed its tests but scope audit and verifier flagged unrelated.md. Correct positive control passed with no edits requested. |
| DEV-WF-04 | Verifier cited PRIORITY-01 failure (untrimmed lowercase result); after one scoped repair, both tests passed and authorization remained unchanged. |

Used `workflow/evaluation/agents/run.py` fixture version 1. Read-only verifier was
gpt-6-luna/low, with five-minute budget and no dispatch rights; implementation
cases used gpt-6-luna/high. Source candidate was c8e66d2 plus workflow changes.
The coordinator retained baseline hashes outside the candidate and reran fixture
checks. Original tests remained unchanged. Scope audits returned nonzero for the
seeded unrelated change; the deliberate failed-check case remained nonzero.
A harness regression checks failure, clean repair and scope detection. Corrected
its sanitized Windows environment to retain SYSTEMDRIVE after an initial fixture
control produced unintended Windows cache paths. No application defect was involved.
Elapsed agent time, complete tool trace and token usage were not instrumented for
these cases; do not infer enforced scope isolation or full process completion.

Rechecked WF-02 and repaired WF-04 through the shared entry point,
`python workflow/verify.py --group fixture --fixture-root <isolated-case>`:
observed exit 1 for the deliberate failure and exit 0 after repair. The wrapper
uses the same frozen unittest command with a sanitized runtime environment.
