# Maintainer migration map

Migrated from the five Maintainer documents at commit `2130dcf`. Those files are
removed from the working tree after coverage review; their history and existing
log/hash evidence remain available through Git. No application behavior or past
verification result is changed by this migration.

| Superseded document | Current OpenSpec source |
| --- | --- |
| `workflow/maintainer/PRODUCT-SPEC.md` | Implemented report-browser baseline; triage, resolution-review, queue-summary and service-integration capability deltas in this change |
| `workflow/maintainer/TECHNICAL-SPEC.md` | design.md proposed APIs, transactions, context/output limits, resource/reuse policies and integration decisions |
| `workflow/maintainer/PLAN.md` | tasks.md MNT-P0 through MNT-P6 mapping and design.md agent/resource/reflection/release policy |
| `workflow/maintainer/READ-ONLY-INCREMENT-SPEC.md` | Main report-browser spec, including the complete proposed query seam and QR-01 through QR-08 |
| `workflow/maintainer/TEST-MATRIX.md` | verification.md MT-01 through MT-26, with approved CL-04/CL-05 corrections |

## Requirement mapping

| Original ID / acceptance | Current owner |
| --- | --- |
| MNT-READ-01 / Q1 | Main frontend baseline plus MNT-READ-SERVER in the service-integration delta for unfinished real services |
| MNT-TRIAGE-01 / T1; MNT-ASG-01 / A1 | maintainer-triage |
| MNT-RES-01 / R1; MNT-DISC-01 / D1 | maintainer-resolution-review |
| MNT-SEC-01 / X1 | maintainer-service-integration plus the action-specific scenarios |
| MNT-AI-01 / S1; MNT-AI-SEC-01 / S2 | maintainer-queue-summary |
| QR-01 through QR-08 | Main report-browser acceptance detail and existing frontend tests |
| MT-01 through MT-26 | verification.md and task-group references |

## Preserved boundaries and corrections

- The already implemented read-only frontend stays in main specs; unimplemented
  behavior stays in proposed deltas. Planning completion does not complete the
  21 future implementation/integration tasks.
- CL-04 provides only the last shared checklist to Maintainers. CL-05 and the
  confirmed storage contract supersede the old blanket checklist-edit invalidation
  wording: Save changes only the private draft revision, unchanged Share is a
  no-op, and changed Share uses draft/report revisions and the shared report lock.
- Current `workflow/tdd.md` and `.codex/agents/` replace the older review sequence
  where they are stricter. Verified Red and feature-owner approval remain required.
- Shared authentication/provisioning, deployment and guide assembly remain separate
  ownership decisions. Nothing from the cancelled allocation is reinstated.
- Historical logs may name removed files and record earlier hashes. They describe
  the old revision and are not active links or current implementation oracles.
