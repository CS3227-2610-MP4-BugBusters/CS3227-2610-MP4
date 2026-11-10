# Maintainer contribution to UserGuide

Prepared these instructions for integration into the team guide. Verified local
application behavior; have not verified a live Render deployment.

## Accounts and access

Sign in with an operator-provisioned account. There is no public registration or
account-management webpage. An operator creates fixed Reporter, Developer or
Maintainer accounts, resets passwords and disables accounts using the backend
commands documented in the authentication README. Resetting a password or disabling
an account invalidates its existing sessions. Sign out when finished.

## Maintainer workflow

Browse reports and filter the queue, then open a report for its source, shared
checklist, discussion and history. Private Developer drafts and notes are excluded.
Set priority, assign an active Developer or reassign with a reason. Reassignment
removes the former Developer's access. Changes use the version currently displayed;
if someone changes the report first, refresh and review before submitting again.

Review the current resolution proposal before returning it for investigation or
closing the report. Supply the requested reason when returning or reopening.
Reopening retains the previous proposal in history. Discussion is plain text;
report content is displayed as text rather than executable markup.

## Queue summary

Request a summary for the current filters. An empty queue returns without an AI
call. Generated findings include report references and context/coverage information;
review the original reports before acting. Closed reports remain browsable, but
summary generation for the Closed filter is disabled.

The application currently has no live shared SoC/budget adapter. A nonempty summary
request reports unavailability while ordinary report actions remain usable. The
explicit development preview uses synthetic output; it does not establish live AI
behavior. No summary automatically changes report state.
