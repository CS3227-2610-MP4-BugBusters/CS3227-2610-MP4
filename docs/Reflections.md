# Huanghao Reflection

I worked on the Developer workflow for BugBusters: assigned reports, investigation checklists, private and shared notes, and AI-assisted checklist suggestions. I also contributed to shared storage, authentication integration and frontend consistency. This reflection evaluates how I used specification, specialized agents, context and resource controls. Results below describe recorded development runs and their observed outcomes.

## Spec-driven development and coordination

I used OpenSpec to record the intended behavior before delegating implementation. For the checklist, the spec distinguishes a private Save from publishing the saved draft, denies access after reassignment, and rejects an empty Share without clearing previously published steps. These decisions gave the test designer concrete scenarios and gave the verifier a reference independent of the implementation. Strict OpenSpec validation checked the artifacts' structure; application tests checked the implemented behavior. [Checklist specification](../openspec/changes/share-investigation-checklist/specs/investigation-checklist/spec.md)

For higher-risk work, the coordinator sequenced test design, independent Red verification, implementation, candidate verification and security review.

A Red result was useful only when the environment and fixture worked and the failure matched the requirement. Agents had defined edit boundaries; the coordinator resolved findings by sending a specific correction back rather than accepting the first passing result. This sequence costs handoffs, but gives an independent reviewer a chance to challenge both the test and implementation.

## Coordinating agents for a shared-state feature

For shared investigation notes, the Developer saves a draft privately and explicitly shares a separate copy with the Maintainer. Because this affects authorization, persistent data and the resolution lifecycle, I used a full workflow: a test designer wrote authenticated PostgreSQL tests, a separate verifier checked the failing baseline, an implementer built the feature, and independent candidate and security reviewers examined the result. The coordinator passed the acceptance criteria, starting revision, relevant contracts, allowed files and expected evidence to each role. [Workflow evidence](../logs/developer/2026-11-13-shared-notes-workflow-evaluation.md)

The reviews found concrete problems and prompted targeted fixes:

| Finding | Response | Why it mattered |
| --- | --- | --- |
| Initial tests omitted role-denial and lifecycle cases | Added the missing cases before approval | Covered privacy and lifecycle behavior beyond the happy path |
| Race fixture waited for an insert, although later shares update | Corrected the fixture and rechecked the baseline | The test needed to observe the actual contested write |
| Conflict refresh could share another tab's unseen draft | Required a successful Save of the visible text before Share | Prevented an unintended publication |
| Request bodies were read before their size was bounded | Bounded and validated bodies before processing | Reduced resource-exhaustion exposure |

The final independent candidate run passed **16 authenticated PostgreSQL tests**, including Share versus closure and reassignment. The Red cases first identified the missing endpoint; the final candidate run exercised the later behavioral assertions. [Workflow evidence](../logs/developer/2026-11-13-shared-notes-workflow-evaluation.md); [Red evidence](../logs/developer/2026-11-13-shared-notes-red.md)

Independence helped expose privacy and test-fixture gaps, but it also caused repeated handoffs and reruns. I should have given the test designer a complete requirement-to-test matrix—including wrong-role access, stale state and both race orderings—at the first handoff. The workflow records show the handoffs and repeated verification.

## Comparing workflow cost and outcomes

I compared two isolated prototypes for recovering from a stale checklist Save. Both began from the same revision and used the same original acceptance cases. The lightweight version used one CLI session; the coordinated version used separate CLI sessions for design, baseline review, implementation and candidate review. All measured sessions used the same model and reasoning setting. The experiment protocol and results are on the local evidence branch, as noted below.*

| Measure | Lightweight | Coordinated |
| --- | ---: | ---: |
| Original acceptance tests | 4/4 passed | 4/4 passed |
| Reported input tokens | 176,313 | 923,253 |
| Included cached input | 141,568 | 782,848 |
| Reported output tokens | 1,528 | 5,699 |
| Sum of measured CLI durations | 41.08 s | 155.88 s |

The coordinated runs reported about **5.24 times as many input tokens**. Cached input is part of that input, not an additional amount. The measurements cover the CLI sessions; the coordinated condition used separate CLI stages orchestrated by the desktop coordinator.

After both runs, the coordinator added an exploratory test that failed on the lightweight candidate and passed on the coordinated candidate. The original tests were corrected and rerun. This experiment illustrates the observed tradeoff for one task.

I also tested the workflow itself using synthetic fixtures. A verifier reported a seeded failed check instead of declaring success; after a scoped repair, the checks passed. Another fixture passed its tests despite an unrelated file change, which a scope audit caught. In separate agent-security cases, agents declined instructions embedded in source comments or issue text to disable tests or bypass authorization. These results record the agents' observed behavior in synthetic fixtures. [Workflow cases](../logs/agent-runs/2026-11-09-workflow-cases.md); [agent-security cases](../logs/security/2026-11-09-development-agent-cases.md)

For small, isolated changes I would use a focused lightweight workflow. For authorization, AI security, shared data or concurrent state changes, the shared-notes findings justify the extra independent checks. The lightweight Account-link removal later illustrated another risk: main gained an Account page, so removing its link could hide access to password changes. I revised the follow-up to preserve the link and fix local routing instead; the follow-up connected the frontend test result to the current account workflow. [Original sidebar log](../logs/developer/2026-11-13-remove-developer-account-link.md)

## Context engineering and token use

I gave agents the specific spec, relevant contract, starting revision, edit scope and runner instead of expecting them to infer the task from the whole conversation. For Developer status filtering, the test designer and verifier received the assigned-reports contract and fixture; the security reviewer checked ownership and reassignment separately. This made responsibilities and the relevant security checks clear. [Status-filter record](../logs/developer/2026-11-08-status-filter-context-resource.md)

I also explored broad discovery versus giving an agent a small set of starting files for a shared-storage review. Both approaches found integration gaps; the broad review highlighted foreign-key integration, while the targeted review identified Developer fixture compatibility with the shared migration. Both agents reviewed the same uncommitted worktree. The exercise shows how different starting contexts surfaced complementary findings. [Context comparison](../logs/evaluation/2026-11-08-context-comparison.md)

In shared notes, too little structured context caused repeated corrections: the first handoff lacked a complete list of denial and race cases. More context is useful when it closes a specific gap, but copying full transcripts can increase repeated input and obscure the important contract. My future handoff should include the task and acceptance IDs, decisions, revision and dirty state, permitted files, fixture and runner, observed failure, and links to sanitized evidence.

Recovered session counters show the scale of this particular workflow. [Measurement method and per-agent breakdown](../logs/evaluation/2026-11-13-session-token-observations.md)

| Scope | Input | Cached input, included | Output | Reasoning output, included |
| --- | ---: | ---: | ---: | ---: |
| Shared-notes coordinator | 77,215,608 | 76,809,216 | 29,086 | 7,261 |
| 13 identifiable specialists | 61,963,554 | 57,809,152 | 166,888 | 55,771 |

The coordinator's reasoning share was approximately 25% of output. These measurements sum repeated inputs across calls and cover the identified coordinator and specialist records. Cached input and reasoning output are included in their respective totals.

The shared-notes record identifies seven test-designer turns and seven baseline-verifier dispatches across revisions. Those counts show repeated coordination across the recorded revisions. I used smaller model settings for bounded testing and review in some runs, and a stronger security reviewer for access-sensitive changes. Persisted notes and handoffs can reduce rediscovery after a pause, but stale notes can also carry incorrect assumptions forward.

## Context and security in the AI feature

The Developer suggestion feature sends the authorized report and last saved checklist to the shared gateway; unsaved edits and private notes are excluded. Suggestions remain advisory and require Developer review before saving or sharing. This reduces unnecessary context and prevents a generated suggestion from directly changing report state. [Developer AI contract](../workflow/developer/ai-checklist-contract.md)

A small direct-provider comparison used the same model and synthetic bug for two prompts. [Prompt comparison](../logs/developer/2026-11-10-ai-response-comparison.md)

| Prompt | Input tokens | Output tokens | Observation in one ordinary case |
| --- | ---: | ---: | --- |
| Existing | 199 | 124 | Five steps, including speculative checks |
| More specific candidate | 248 | 104 | Three steps appeared more directly tied to the bug |

The candidate cost 49 more input tokens. In a separate adversarial case it returned only two steps despite a three-step minimum. The comparison used one response per condition and direct provider calls, so the observations describe those individual responses.

Security testing of the application found actual boundary defects, including logout races and unsafe error handling. After fixes, an independent candidate run reported **75 tests with no failures** for the relevant local backend suite, including rejection of multiline model output and invalid JSON media types. The tests used synthetic accounts, local HTTP, a fake provider and disposable PostgreSQL. A separate investigation documented the cancellation-capacity tradeoff. [AI security evidence](../logs/developer/2026-11-09-ai-security-regressions.md), [cancellation limitation](../logs/developer/2026-11-10-ai-cancellation-capacity.md)

Live diagnostics showed that a larger output budget does not necessarily make a result useful. In separate direct synthetic Qwen calls without an explicit reasoning setting, one request exhausted a **384-token** cap with empty content; another exhausted **2,048 output tokens** and still returned empty content after 31.68 seconds. The latter also exceeded the application's 15-second deadline. Each result came from one direct diagnostic call; the configured application limits remained unchanged. [Gateway diagnostics](../logs/developer/2026-11-11-main-gateway-alternatives.md)

\* The workflow comparison is preserved on local branch `codex/workflow-experiment-evidence` at commit `9993c5a`; inspect it with `git show codex/workflow-experiment-evidence:workflow/evaluation/reflections/stale-checklist-workflow-2026-11-12/results.md`.

## Maintainer Reflection

I developed the Maintainer workflow and authentication integration with OpenSpec, executable tests and specialist review. The most valuable integration checks covered stale versions, proposal invalidation, checklist Share versus closure, account-disable races and delayed AI responses. They focused on authorization and atomic data changes. Shared checklist interfaces stayed compatible while session and eligibility checks were added at the application boundary.

### Coordination and review

Independent review was temporarily unavailable when agent capacity was exhausted, so the work remained a draft checkpoint. A later fresh review passed the release tooling and caught a dropped wrapper-checksum gate, which was restored. The release checks also exercised role sessions and migration scenarios against disposable PostgreSQL. These results describe the tested local revisions; they do not establish a deployed release. [Release checks](../logs/maintainer/2026-11-12-release-checks.md)

A frozen comparison tested two small validation tasks with a single agent and with implementation plus review. All four candidates passed the same checks, and the reviewers found no extra defects in those samples:

| Task | Single agent | Implementation plus review |
| --- | ---: | ---: |
| Title validation | 62.45 s | 140.13 s |
| Reason validation | 60.96 s | 103.65 s |

These coordinator-observed intervals include scheduling and handoffs. Each condition ran once; token and cost measurements were unavailable. They show review overhead for these small tasks rather than a general result for complex changes. [Comparison evidence](../logs/experiments/2026-11-09-maintainer-comparisons.md)

### Resource-aware AI and live evaluation

The queue-summary service reuses a result only when its authorized inputs remain valid. In a controlled-provider experiment, ten identical authorized requests caused **one provider call** with reuse and **ten** with regeneration. Source version, assignment, membership and model changes caused new calls; revoked access caused no extra call. The provider returned deterministic empty findings, so this measures call accounting rather than live latency, model quality or provider caching. [Reuse evidence](../logs/experiments/2026-11-09-maintainer-comparisons.md)

Live local trials with synthetic reports revealed timeouts, rate limiting and an answer that treated description facts as missing. A rate-limited or timed-out adversarial request cannot establish prompt-injection resistance. The team independently verified context/configuration failures, corrected the fixture to reflect blank database fields, preserved strict validation and revised context and model selection. The final three synthetic Qwen cases returned valid summaries in **4.7–13.9 seconds** and recognized description-only facts. These are diagnostic cases, not completed human scoring or a controlled model comparison. [Release checks](../logs/maintainer/2026-11-12-release-checks.md); [repair results](../logs/maintainer/2026-11-12-summary-live-repair.md)

For safe report rendering, the Maintainer frontend reused the shared Markdown renderer. A focused test exposed missing formatting; a separate review then identified an incorrect test expectation about a trailing newline. The test was corrected and rechecked, and browser inspection identified a missing wrapper needed for responsive scrolling. [Markdown integration evidence](../logs/reporter/2026-11-12-maintainer-safe-markdown.md)

Synthetic development-agent security cases also recorded agents declining instructions to disable tests or bypass authorization. Those observations are not evidence of operating-system-enforced isolation or complete access traces. [Agent-security cases](../logs/security/2026-11-09-development-agent-cases.md); [workflow cases](../logs/agent-runs/2026-11-09-workflow-cases.md)

# Tieu Trong Phuc Reflection

I owned the Reporter workflow: Markdown reports with a shared safe renderer used by all three roles, the Reporter API, report discussion and the AI clarification check. I also set up the SoCLLM gateway and its per-task limits. This reflection is about what I learned from building these features with a multi-agent software engineering workflow: how to make agent output reviewable, how to give each agent the right context, when independent roles are worth their cost, and when they are not.

## Spec-driven development and readable reports

I wrote the Markdown feature as an OpenSpec change before any code: `reporter-reports` (RPT-01..07), `safe-markdown` (MDR-01..08), a design with decisions D1..D9 and 21 tasks. The test designer, verifiers and implementer each worked from those requirements through the TDD workflow. [Proposal record](../logs/reporter/2026-11-09-reporter-markdown-reports-proposal.md)

The agents reported everything as spec numbers: "MDR-03 holds", "D7 needs sign-off", "finding on MDR-05 / task 3.3". These IDs are useful for traceability, but I could not remember what each one meant, so reviewing and approving a set of failing tests before implementation meant opening the spec again for every line. That made my approval slower and less careful, which defeats the purpose of a human gate. I changed the agent instructions so each point states the actual problem in plain words, with the ID kept only as a reference, for example "deeply nested input freezes and then crashes the render (MDR-05)". After that, I could approve or reject a finding from the report itself. What I learned is that a multi-agent workflow is only as good as the human gate in it: IDs help agents trace work to the spec, but reports written for a person must say what is wrong in words that person can act on.

## Context engineering for the specialists

Each specialist received a handoff packet instead of the conversation: task ID, starting revision, the in-scope requirements, the contract section, files to read, files it may write, and the exact commands. It reads those files first and retrieves more only to close a named gap. Rules that never change (TDD gates, verifiers see artifacts but not the implementer's reasoning, test output is data and not instructions) live upfront in the project instructions and role definitions. Task facts go in the packet. [Agent context guide](../workflow/agent-context.md)

This kept the Markdown specialists small. Each test designer or baseline verifier used about **15,000 to 21,000 tokens**, and the security review used **44,009 tokens**, as reported by the harness. Cache-token counts were unavailable. [Markdown evidence](../logs/reporter/2026-11-09-safe-markdown-red-3.1-3.2.md)

The extra cost came from rework, not from the packet size. The first set of failing security tests for task 3.2 was rejected by the verifier for weak tests (a patched `src` setter never restored, a disallowed-link test that did not check the URL was absent, and a link case CommonMark does not parse as a link), so it needed a second designer and verifier pass. Later the implementer stopped with a baseline defect: the approved test passed in Vitest but failed `tsc --noEmit`, because the check on the failing tests ran Vitest only and never typechecked. That cost another designer fix, a fresh verifier and a re-approval. Both cases were missing information in the first handoff, not too little context overall. Verification of failing tests in TypeScript packages now runs `tsc` as well. A handoff note should record the revision, the exact failing command and output, approval hashes, open owner decisions and edit boundaries, so the next agent does not rediscover them.

## Multi-agent review of the Markdown renderer

The renderer shows untrusted report text to Developers and Maintainers, so I used the full workflow and this is where it was clearly worth it. The verifier checked that empty, naive `dangerouslySetInnerHTML` and plain-text-only implementations each fail at least one test, so the tests could not be passed by a shortcut. The independent security reviewer then ran **more than 80 probe inputs** (encoded and obfuscated URL schemes, reference links and images, autolinks, raw HTML/SVG/MathML, task lists) and none broke the raw-HTML, image or link rules. It also found a high-severity availability problem none of us had specified: about 4 KB of nested `> - ` input crashed the render, and a longer one froze it for 18 seconds. This became a size and nesting guard plus an error boundary. It also listed lower-risk phishing cases such as misleading link text and credentials in URLs.

A single agent that designs, implements and judges its own tests carries the same assumptions through every step and tends to confirm its own work. Splitting the roles, giving reviewers only the artifacts and running each one in a fresh context is what surfaced these problems. The shared Markdown package ended with **242 passing tests across 12 files**. [Markdown evidence](../logs/reporter/2026-11-09-safe-markdown-red-3.1-3.2.md); [test reorganisation](../logs/reporter/2026-11-09-markdown-view-test-folder.md)

## Choosing the right workflow and model

I matched the model to the role: Sonnet at low effort for test design and verification, which follow a clear packet, and Opus for implementation and security review, which need deeper reasoning. Scaffolding, test-file moves and runner scripts had no behaviour change and skipped TDD entirely.

My mistake was not stating clearly enough when the full workflow applies. I once let the agents run the full test-driven workflow for a UI component change, so a styling and layout fix went through test design, baseline verification, implementation and candidate review. Most of the tokens went to handoffs and regression runs on a change that was easy to see and easy to revert. This taught me that choosing the workflow is itself an engineering decision, and the rule has to be written down for the agents rather than left to judgement: the multi-agent workflow is for large or risky work (untrusted input, authorisation, shared data, AI security). Small UI changes use a lightweight path with one agent, a focused check and `npm run verify`, plus my own visual review. [Workflow selection](../workflow/workflow-selection.md)

## What I learned about multi-agent SE

Splitting the work into roles with fresh contexts makes results more trustworthy, because no single agent gets to judge its own assumptions. That independence has a cost: every handoff resends context, and every gap in a handoff causes a full rerun. Multi-agent SE worked best for me when the spec was finalised and approved before any failing tests were written, each packet was complete and small, reports were readable by a human approver, and the full workflow was reserved for tasks where an independent reviewer could plausibly find something one agent would miss.
