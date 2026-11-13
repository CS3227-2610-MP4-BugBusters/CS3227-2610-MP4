# BugBusters user guide

BugBusters helps Reporters submit bugs, Developers investigate them, and Maintainers assign and review work. Open the application address provided by your team. The instructions describe the integrated main-branch features; the application available to you may differ.

For a local exercise, ask the operator for a running instance and active Reporter, Developer and Maintainer accounts. Use a disposable database and fictional reports. Do not enter passwords, personal information or real customer data into reports or AI requests. Operators can follow the [account setup](../src/backend/auth/README.md) and [local deployment instructions](../src/backend/platform/deployment/README.md).

## Getting access

A Maintainer creates accounts for Reporters, Developers and other Maintainers. Ask a Maintainer for your username and initial password. Each account has one role, and credentials should be shared privately.

**For public demo visitors:** In a deployment that includes the account-management feature, use the shared demo Maintainer account described in the [account setup documentation](../src/backend/auth/README.md) to try the application without contacting the team. Open **Account** and create separate Reporter, Developer and Maintainer accounts. Sign out and sign in with each account to try its role. Ordinary accounts can change their own password from the **Account** page. Account holders can also update their display name.

**Public demo limitation:** Anyone with the published demo credentials can sign in as a Maintainer, view or change demo data, and create accounts. Use only fictional, disposable data; do not enter personal, confidential or real customer information. Keep the demo database and secrets separate from environments containing real data.

This shared account is an intentional convenience for an open demo. A production deployment for real users needs privately provisioned Maintainer credentials and the public demo account disabled. The current account-management implementation needs further changes to support that safely.

## Sign in and sign out

1. Open the application root (`/`) and enter your account credentials.
2. Select **Sign in**. The app directs Reporter and Developer accounts to `/reporter/` and `/developer/`; Maintainers remain at `/`.
3. Select **Sign out** when finished or before switching roles. Separate browser profiles can be used to test roles at the same time. Direct role addresses remain available.

Each account has one role. If sign-in fails, check your details and role with the operator. There is no public self-service registration or password reset; an account holder can change their password from the Account page where account management is available. After repeated failed attempts, wait before retrying.

## Reporter

### Quick start and interface tour

Use a current browser to open the team's address ending in `/reporter/`. A
Maintainer creates your Reporter account and gives you the username and initial
password privately. Sign in with that account; each account has one role.

| Screen | What you can do |
| --- | --- |
| Report list | Browse your reports, search titles on the current page, and select **Open**, **Closed** or **All**. |
| New report | Enter a title and Markdown description, use **Write** and **Preview**, request **Check my report**, and submit. |
| Report page | Read the description, status, details and discussion; edit an eligible report or add a comment. |

Statuses show progress through **Submitted**, **Assigned**, **In progress**,
**Resolution proposed** and **Closed**. A Maintainer manages assignment and
closure. Reporters see only their own reports.

### Create a report

1. Select **New report**.
2. Enter a one-line **Title** of up to 200 characters and a **Description** of up to 20,000 characters. Fill in the template with what happened, steps to reproduce, and what you expected. Use Markdown lists or fenced code blocks for steps and logs.
3. Optionally select **Check my report** and review its questions as described below. Edit the draft yourself, select **Preview** to review the formatting, then select **Submit report**.
4. Open the new report and note its reference for the Maintainer.

For example, use this fictional report:

**Title:** Checkout total stays unchanged after removing an item

```markdown
### What happened?
In the sample shop, removing a $5 item leaves the displayed total at $15.

### Steps to reproduce
1. Add a $10 item and a $5 item to an empty cart.
2. Remove the $5 item.
3. Look at the displayed total.

### What did you expect?
The displayed total should change to $10.
```

A failed submission keeps the draft in the form. You can create up to 3 reports
per minute; if a rate-limit message appears, wait for the displayed time before
retrying. Unsaved drafts are kept only on the current screen. Copy text you want
to keep before choosing **Cancel**, reloading or leaving the page. Successfully
submitted reports remain available when you return.

### Check my report (AI)

**Check my report** asks the team's AI service which parts of your new draft are
unclear and what details a Developer may still need. It sends the current title
and description for that check. The application does not save the check's draft
or questions, and the AI never changes your text or submits a report.

1. On **New report**, enter a title and description.
2. Select **Check my report**. The button shows **Checking…**, and a progress
   message explains that the check can take up to a minute.
3. Read **Questions about your report**. **Unclear parts** quotes exact words
   from your draft and asks what they mean. **Questions by topic** asks about
   Summary, Steps to reproduce, Expected behavior, Actual behavior, Environment,
   Frequency, or Error messages and logs. Only topics returned by the AI appear.
4. Answer useful questions by editing your draft. Select **Dismiss** to close
   the panel, or run another check for an updated draft.
5. Select **Preview**, then **Submit report** when you are ready.

**Worked example:** Start with the fictional title `Checkout total stays unchanged
after removing an item` and description `the total is wrong after i remove
something`. The AI may ask which item you removed, what total you expected, or
which browser you used. Reproduce the issue yourself and add truthful details,
using the completed report above as an example. The exact questions can vary.
If the service is unavailable, write these details manually and submit as usual.

The check runs only when you select the button and is available only for new
reports. It allows up to **3 checks per account in 5 minutes**; shared service
limits can also require you to wait. An empty result displays **No questions
found. Your report looks clear.** This is advice, not proof of completeness or
evidence that anyone tested the bug.

Results are plain text, with no active links or formatting. They disappear on
**Dismiss**, a new check, submission or leaving the screen. Editing the draft
does not update earlier questions automatically. Ignore irrelevant or incorrect
questions; you decide what belongs in the report.

Do not send passwords, private customer information or confidential logs. The
draft is sent to an external AI service, and the application's decision not to
store check results does not establish that provider's retention policy.

### If a report check fails

| Message or situation | What to do |
| --- | --- |
| **Checking isn't available right now. You can still submit your report.** | Continue manually; ask the operator to check the service if needed. |
| **You've checked too often. Try again in … seconds.** | Wait the displayed time before retrying. |
| **A check is already running.** | Wait for it to finish before requesting another check. |
| **Couldn't check your report. Try again.** | The response could not be accepted. Retry later or submit without checking. |
| **The check took too long. Try again later.** | Retry later; keep relevant logs concise. |
| A title or description validation message | Correct the indicated field before checking or submitting. |

### Reporter limitations and frequently asked questions

**Can I check an existing report?** The AI action appears only on **New report**.
You can edit an existing report while it is Submitted and unassigned, or add
more information in a comment.

**Why does a topic ask about something I already wrote?** Questions can ask for
more detail about partly covered topics. Skip any that do not help.

**Does the AI rewrite or save my report?** It returns questions only. You edit
the text, and **Submit report** saves it. Results are not kept for later visits.

**Why do answers change?** Each check generates a new answer. The locally
evaluated `x-test-2` model is a provider test slot whose backing model may change.
The server also removes questions caught by its URL, email or credential screen.

The Reporter screen does not show the Developer checklist or offer per-step
responses. Title and status filters apply only to the currently loaded page.

### Find and edit a report

The report list contains your own reports. Use the title field or **Open**, **Closed**, and **All** filters to narrow the current page. Use the page controls to check other pages.

Open a report and use its edit controls to change its title or description. Editing is available while the report is Submitted and unassigned. After assignment, ask a Maintainer to help with corrections. If another change causes a conflict, reload the report and review it before trying again.

### Add a comment

1. Open your report and scroll to **Add a comment**.
2. Enter your message and select the submit button. Markdown is supported.
3. Check that the comment appears in the discussion.

You can comment after a report is closed. For example, reply `After refreshing, the total still shows $15.` when you have actually checked it. The Maintainer can read the discussion. The Developer screen in this revision does not show it, so ask the Maintainer to relay relevant details.

## Developer

### Find and start an assigned report

1. Sign in as the assigned Developer.
2. Under **Assigned reports**, use **Search** or **Status** to find the report, then select its title.
3. Read the description and select **Start investigation**. Check that the status becomes In progress.

Only reports currently assigned to you appear. If a report is missing, check the filters and pages or ask the Maintainer about its assignment.

### Save private notes

Open the assigned report, enter your observations under **Private investigation notes**, and save them. These notes are visible only to the assigned Developer. Refresh and reconcile your text if a newer version has been saved elsewhere.

### Share investigation notes with Maintainers

Shared investigation notes are available in builds containing this feature; verify the deployed revision.

1. Open the assigned report and find **Shared investigation notes**. This editor is separate from **Private investigation notes**.
2. Enter the findings you want Maintainers to see, then select **Save shared draft**.
3. Select **Share saved note**. The Maintainer can read the last saved and shared version under **Investigation notes**; the Reporter cannot read these notes.
4. To change the published note, edit and save the draft, then share again. Typing or saving alone does not publish changes. If a conflict occurs, refresh the saved revision, review your text and save it before sharing.

Sharing a changed note during resolution review returns the report to In progress and supersedes the current proposal. Private investigation notes remain private and are never automatically copied into this editor.

### Create and share an investigation checklist

1. Open the report and find **Investigation checklist**.
2. Enter a check in each step field. Select **Add step** for additional checks.
3. Select **Save** to save the private draft.
4. Select **Share** to publish the last saved steps. At least one saved step is required.
5. Ask the Maintainer to open the report and confirm the shared checklist appears.

For the sample cart report, checks could include reproducing the problem, checking the total after a refresh, and verifying the total after removing an item. Save again before sharing any later edits: **Share** publishes the saved draft, not unsaved text.

### Generate checklist suggestions

1. Save any checklist steps you want the AI to consider.
2. Select **Generate suggestions**.
3. Review the suggested steps and select the useful ones.
4. Select **Add selected** or **Replace checklist**. Edit the resulting steps, then **Save** and **Share** when ready.

Suggestions use the report and last saved checklist. Unsaved edits and private notes are not sent. AI output is advice; check it before using it and do not treat it as proof that a test was performed. AI requires the operator to configure an available provider, model, role key and budget. If generation fails, write the checklist manually. See [Developer AI configuration](../src/backend/auth/README.md#local-developer-ai-configuration).

### Propose a resolution

1. Investigate the report and complete the relevant checks.
2. While the report is In progress, enter the work and results in **Resolution**.
3. Select **Submit resolution**. The report moves to Resolution proposed for Maintainer review.

For example, after actually fixing and checking the fictional cart issue, a resolution might say: `Updated the cart total after item removal. With $10 and $5 test items, removing the $5 item now displays $10.` Describe only work and results that actually occurred.

If the Maintainer returns the proposal, read **Maintainer returned this resolution**, perform the requested work, and submit a new resolution.

## Maintainer

### Find and inspect a report

Open **Report queue** and select a report. Use the status, priority and assignee filters to narrow the queue. The report view includes its description, shared checklist, discussion, current proposal and history. Private Developer notes and unshared checklist drafts are not shown. Report descriptions support Markdown lists, tables and code blocks; HTML is displayed literally, images are not loaded and links are not clickable. Titles and other fields remain plain text.

### Set priority and assign a Developer

1. Open the report and find **Priority and assignment**.
2. Choose a priority and select **Save priority**.
3. Under **Assign to**, select an active Developer and choose **Assign**.
4. Check that the report shows the selected assignee and status Assigned.

To reassign an eligible report, select a different Developer, enter a reason and choose **Reassign**. The previous assignee loses access. If the report has changed since you opened it, refresh and review it before retrying.

### Add a discussion comment

Open the report, enter a message under **Add discussion comment**, and select **Add comment**. For example, ask `Does the total update after refreshing the page?` The Reporter can reply from their report page. The Developer screen in this revision does not show this discussion; relay any information needed for investigation.

### Review a resolution

1. Open a report in Resolution proposed and read **Current proposal**, the checklist and relevant history.
2. To accept it, check **I reviewed this proposal** and select **Close**. Confirm that the status is Closed.
3. To request more work, enter a **Return reason** and select **Return resolution**. The report returns to In progress, and the Developer can see the feedback and submit another proposal.

For example, a return reason could be `Please verify that the total remains correct after refreshing.`

### Reopen a report

Open a Closed report, enter a **Reopen reason**, and select **Reopen**. The report returns to Submitted and needs to be assigned again. Earlier activity remains in the history.

### Generate a queue summary

1. Set the queue filters you want to summarize.
2. Select **Generate summary**.
3. Review the findings, their report references, and the included and excluded reports. Open the source reports before acting.
4. Select **Regenerate summary** if you need another result.

Summary generation is unavailable for the Closed filter. An empty matching queue does not require an AI call. A configured and available provider, model, Maintainer key and budget are needed for a live summary; see [Maintainer configuration](../src/backend/maintainer/README.md). If generation fails, ordinary report actions remain available. A timeout or rate-limit error does not change report state; review the source reports before acting on a summary.

## Try the complete workflow

Use three synthetic accounts and the sample report above:

1. **Reporter:** Submit the report and note its reference. Confirm it is Submitted.
2. **Maintainer:** Find the report, set its priority and assign the Developer. Confirm it is Assigned.
3. **Developer:** Open the report, start investigation, save and share a checklist. Confirm it is In progress.
4. **Maintainer:** Open the report and check the shared checklist.
5. **Developer:** After performing the investigation, submit a truthful resolution. Confirm it is Resolution proposed.
6. **Maintainer:** Review the proposal and close it, or return it with a reason for another attempt.
7. **Reporter:** Open the report and check its status.

The Reporter can optionally use **Check my report** before step 1. The Reporter
screen does not show the checklist or offer per-step responses, and AI assessment
of those responses is not exposed. The standalone checklist demo uses synthetic
in-memory data and does not establish those features in the shared application.

## If something goes wrong

| Situation | What to do |
| --- | --- |
| Sign-in fails or access is denied | Check the account, role and workspace with the operator. |
| A report is missing | Check the current page, filters and assignment. Reporters see their own reports; Developers see assigned reports. |
| A report or checklist has changed | Preserve any unsaved text, refresh, review the current version and retry the intended change. |
| AI is unavailable or limited | Continue manually, or ask the operator to check configuration and quota. |
| The outcome of an action is unclear | Refresh and inspect the report before submitting again. |
