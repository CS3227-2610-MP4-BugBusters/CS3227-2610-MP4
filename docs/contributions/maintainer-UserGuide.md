# Maintainer contribution to UserGuide

Prepared these instructions for integration into the team guide. Verified local
application behavior; have not verified a live Render deployment.

## Accounts and access

Added a fixed shared demo account: username `demo.maintainer`, password
`demo-maintainer-2026`, display name `Demo Maintainer`. Kept that account's name
and password unchanged so everyone can continue using it.

Added an **Account** link to each role. Maintainers can create accounts there for
all three roles and privately give each person their initial credentials. Ordinary
accounts can change their own password by supplying their current password;
this signs out all their sessions. There is no public signup or forgotten-password
page. Retained operator commands for creating, resetting and disabling ordinary
accounts. Each account keeps one fixed role.

## Maintainer workflow

Browse reports and filter the queue, then open a report for its source, shared
checklist, discussion and history. Private Developer drafts and notes are excluded.
Set priority, assign an active Developer or reassign with a reason. Reassignment
removes the former Developer's access. Changes use the version currently displayed;
if someone changes the report first, refresh and review before submitting again.

Review the current resolution proposal before returning it for investigation or
closing the report. Supply the requested reason when returning or reopening.
Reopening retains the previous proposal in history. Added safe Markdown formatting
for report descriptions, including lists, tables and code blocks. HTML appears
literally; images are not loaded and links are not clickable. Kept titles, other
report fields and discussion as plain text.

## Queue summary

Request a summary for the current filters. An empty queue returns without an AI
call. Generated findings include report references and context/coverage information;
review the original reports before acting. Closed reports remain browsable, but
summary generation for the Closed filter is disabled.

Connected the shared SoC adapter and database-backed usage controls. Live summaries
require the server's Maintainer API key, selected model and application budgets;
without that configuration, generation reports unavailability. Kept the explicit
development preview separate from the real application.

Initial real-summary trials on 12 November timed out or incorrectly described
information in the description as missing. Repaired the context/instructions and
added a separately configured Maintainer model. The final Qwen trials returned
valid summaries in 4.7–13.9 seconds and recognized the description-only facts.
Recorded the small-sample follow-up in
[the repair results](../../logs/maintainer/2026-11-12-summary-live-repair.md).
Treat the summary as advice and check the original reports before acting. A timeout
or rate-limit error leaves report actions available and changes no report state.
Have not established broad live AI prompt-injection resistance, provider quotas or Render.
See the [release check results](../../logs/maintainer/2026-11-12-release-checks.md).
