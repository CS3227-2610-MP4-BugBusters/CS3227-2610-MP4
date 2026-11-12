# BugBusters user guide

BugBusters helps Reporters submit bugs, Developers investigate them, and Maintainers assign and review work. Open the application address provided by your team. This guide describes the screens in checkout `26f80a2`; the application available to you may differ.

For a local exercise, ask the operator for a running instance and active Reporter, Developer and Maintainer accounts. Use a disposable database and fictional reports. Do not enter passwords, personal information or real customer data into reports or AI requests. Operators can follow the [account setup](../src/backend/auth/README.md) and [local deployment instructions](../src/backend/platform/deployment/README.md). There are no default credentials in this guide.

## Getting access

A Maintainer creates accounts for Reporters, Developers and other Maintainers. Ask your team’s Maintainer for a username and temporary password, then sign in and change your password from the **Account** page. Each account has one role.

**For reviewers:** Your team will privately provide a Maintainer account so you can create separate accounts for each role and test the complete workflow. Use fictional reports and sample data during testing.

For a new installation, the operator privately provisions the first Maintainer using the [operator command](../src/backend/auth/README.md#operator-command). The Maintainer can then create accounts from the Account page. These instructions depend on the secure account-management changes being integrated.

## Sign in and sign out

1. Open the address for your role: `/reporter/`, `/developer/`, or `/` for Maintainer.
2. Enter the username and password supplied by your operator, then select **Sign in**.
3. Select **Sign out** when finished or before switching roles. Separate browser profiles can be used to test roles at the same time.

Each account has one role. If sign-in fails, check your details and role with the operator. There is no self-service registration or password reset. After repeated failed attempts, wait before retrying.

## Reporter

### Create a report

1. Select **New report**.
2. Enter a short **Title** and describe the problem in **Description**. Include what happened, steps to reproduce, and what you expected.
3. Select **Preview** to review the formatting, then select **Submit report**.
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

A failed submission keeps the draft in the form. If a rate-limit message appears, wait for the displayed time before retrying.

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

Open **Report queue** and select a report. Use the status, priority and assignee filters to narrow the queue. The report view includes its description, shared checklist, discussion, current proposal and history. Private Developer notes and unshared checklist drafts are not shown.

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

Summary generation is unavailable for the Closed filter. An empty matching queue does not require an AI call. A configured and available provider, model, Maintainer key and budget are needed for a live summary; see [Maintainer configuration](../src/backend/maintainer/README.md). If generation fails, ordinary report actions remain available.

## Try the complete workflow

Use three synthetic accounts and the sample report above:

1. **Reporter:** Submit the report and note its reference. Confirm it is Submitted.
2. **Maintainer:** Find the report, set its priority and assign the Developer. Confirm it is Assigned.
3. **Developer:** Open the report, start investigation, save and share a checklist. Confirm it is In progress.
4. **Maintainer:** Open the report and check the shared checklist.
5. **Developer:** After performing the investigation, submit a truthful resolution. Confirm it is Resolution proposed.
6. **Maintainer:** Review the proposal and close it, or return it with a reason for another attempt.
7. **Reporter:** Open the report and check its status.

The Reporter screen in this checkout does not show the checklist or offer per-step responses. Reporter AI report improvement and AI assessment of checklist responses are also not exposed in the inspected screens. The standalone checklist demo uses synthetic in-memory data and does not establish that those features work in the shared application.

## If something goes wrong

| Situation | What to do |
| --- | --- |
| Sign-in fails or access is denied | Check the account, role and workspace with the operator. |
| A report is missing | Check the current page, filters and assignment. Reporters see their own reports; Developers see assigned reports. |
| A report or checklist has changed | Preserve any unsaved text, refresh, review the current version and retry the intended change. |
| AI is unavailable or limited | Continue manually, or ask the operator to check configuration and quota. |
| The outcome of an action is unclear | Refresh and inspect the report before submitting again. |

This guide was checked against repository screens, handlers and documentation; the full three-role flow was not run for this revision. The locally recorded `origin/main` includes the Developer AI refinement and is ahead of this checkout, including a later Developer Markdown change. Other branches, the concurrent Developer navigation work and a running deployment may differ; confirm their revision before relying on their features.
