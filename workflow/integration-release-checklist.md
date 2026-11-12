# Integration and release checklist

Use this checklist for the candidate revision being considered for release.
For each item, record the owner, revision/environment, command or steps, outcome,
evidence location, and any limitation. An unchecked item is not evidence that a
feature is absent. Do not include credentials, private data, or provider secrets.

## Candidate and shared integration

- [ ] Identify the exact commit, branch/PR and clean or dirty worktree state;
  reconcile review findings and hosted checks for that revision.
- [ ] Run relevant checks through `python workflow/verify.py` and record any
  separate shared-storage, migration or integration checks. Record unavailable
  dependencies and failed checks; do not turn a blocked run into a pass.
- [ ] On an isolated database, test fresh migrations and the supported upgrade
  path with preserved data. Document unsupported historical migration paths.
- [ ] Exercise real authenticated role boundaries and the integrated journey:
  Reporter creates a report, Maintainer assigns it, Developer investigates and
  privately saves then shares a checklist, Reporter sees only the shared copy,
  Developer proposes a resolution, and Maintainer reviews it. Record API and
  browser checks separately; include reassignment and denial cases.

## Shared AI and security

- [ ] With the Reporter/gateway owner, verify the Developer integration against
  the shared gateway: actual model, available quota and shared admission limits,
  configured timeout/capacity behavior, and sanitized usage evidence where exposed.
- [ ] Run frozen legitimate and adversarial cases, authorization and input/output
  bounds, stale result/reassignment checks, and timeout/error handling. Record
  usefulness and security outcomes separately; a timeout is an availability
  failure, not proof of injection resistance. Use synthetic data and no secrets.
- [ ] Complete applicable independent security review and repository security
  checks. Track findings, fixes, rechecks and unresolved risks with evidence.

## Environments and deployment

- [ ] Verify distinct development and production database identifiers and
  credentials, plus separate environment-specific AI secrets. Record sanitized
  evidence, never secret values.
- [ ] Record the deployed revision and verify a successful login and authorized
  database read in each environment. Distinguish deployed evidence from local
  fixtures, mocks and direct provider calls.
- [ ] The deployment owner verifies GitHub's actual production environment
  approval settings and records the result; a workflow environment name alone
  does not establish that a required reviewer is configured.
- [ ] Reconcile OpenSpec task checkboxes against their complete stated checks,
  update guides and acknowledgements, and link verification and reflection
  evidence. Escalate unresolved release decisions to their owner.
