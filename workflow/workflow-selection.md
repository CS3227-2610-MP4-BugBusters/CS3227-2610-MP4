# Choosing the development workflow

Choose the lightest path that covers the task's risks. Record the chosen path,
reason, checks and their limits in the task evidence. If scope or risk changes,
switch to the full path before implementing the newly identified sensitive work.
Explicit owner instructions and applicable acceptance criteria still apply.

| Path | Use for | Process |
| --- | --- | --- |
| Lightweight | Clear, low-risk, isolated work, such as presentation edits, documentation, or a small UI change without security or shared-state effects. | One agent may do the work and review its diff. For a behavior change, use a focused test and reproduce the failure before the fix when practical; run relevant regressions. For non-behavior work, inspect the result. Record what ran. |
| Full | Authorization, authentication, AI security or provider boundaries, shared data or migrations, cross-role lifecycle, concurrency, or uncertain/high-impact behavior. | Follow the numbered gates in [tdd.md](tdd.md): independent Red verification, approved baseline, implementation and fresh candidate verification. Authorization and AI security also require a separate security reviewer. |

When uncertain, inspect the affected behavior first. Treat uncertainty about
authorization, data integrity, or AI safety as full-path work until resolved.
The owner can request a stronger path for any task. A lightweight path does not
replace required security, integration, or release checks.

For the full path, keep handoffs focused: task and criteria, relevant paths,
revision, edit boundaries and expected checks. Independent reviewers receive the
artifacts and evidence needed to verify, rather than the implementer's reasoning.
If a required independent reviewer is unavailable, report the missing review and
do not present it as passed. Record available usage and process evidence under
the [TDD evidence format](tdd.md#evidence), marking missing measurements unavailable.
