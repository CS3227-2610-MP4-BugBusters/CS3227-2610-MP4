# Approval: reporter-report-clarify task 2.4

## Summary

Implementation proceeds under the owner's standing approval for group 2
(2026-11-13, "auto approve") after a fresh baseline verifier returned **PASS**. The
baseline covers owner decision (b): a screened question drops only its own item, and
an all-dropped result is a safe error. It also covers RC-07c: hidden, format and
direction-mark characters, or no visible character, reject the whole result. No
previously approved assertion changed. The existing single-item RC-07b assertions
stay valid, because a single dropped item means all items are dropped. Non-blocking:
the no-visible-character cases overlap with default-ignorable ones, and the
all-dropped 502 is asserted at the rules level plus the existing service test.

## Approved baseline

- Revision: HEAD `1b2d3b7` (branch `llm-reporter`) plus the groups 1-3 working tree.
- `ReportClarifyDropScreenedTest.java` `c14a442debebe510ebc07656eb421e547f643008ed53ae3a81b597add4bbb856` (new)
- `ReportClarifyRulesTest.java` `a62f53959e6613bd70e6a8f9e0eadd5516e945ee588503970bad588b45b64270` (unchanged)
- `ReportClarifyScreeningHardeningTest.java` `6ccd86516f31e4f57413e6fa251802b584e07f86aa601b1868d4c1a81e7639d6` (unchanged)
- `ReportClarifyServiceTest.java` `40b4487e76078d00df3ceba91b39a035106ba5ef71c5a35331e2a027638dd0ff` (unchanged)
- Red: 70 tests, 10 failed, all in the new file ([red-group2-round2.md](red-group2-round2.md)).
