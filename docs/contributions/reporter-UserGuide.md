# Reporter contribution to UserGuide

Prepared these instructions for the Reporter part of the team guide. Checked them
against branch `llm-reporter` (base `1b2d3b7` plus uncommitted Reporter AI work)
running locally with a disposable database. A live Render deployment has not been
checked; the screens available to you may differ.

## 1. Quick start

### 1.1 Get an account

A Maintainer creates Reporter accounts and gives you a username and initial password
privately. Each account has one role. There is no public sign-up and no
forgotten-password page; you can change your password from **Account** after signing
in.

### 1.2 Sign in

1. Open the Reporter address supplied by your team. It ends in `/reporter/`.
2. Enter your username and password, then select **Sign in**.
3. Select **Sign out** when you finish, or before switching to another role.

Only an active Reporter account can open this workspace. A Developer or Maintainer
account is refused before any report is shown.

## 2. Interface tour

| Screen | What it shows |
| --- | --- |
| **Report list** | Your own reports, 25 per page, newest first. Each row has an open or closed icon, a status label and the title. |
| **New report** | Title, a Markdown **Description** with **Write** and **Preview** tabs, **Check my report**, **Cancel** and **Submit report**. |
| **Report page** | An open/closed badge, a timeline that starts with your description, the comments after it, and a sidebar with details. |

Status labels follow the bug's progress: **Submitted**, **Assigned**,
**In progress**, **Resolution proposed** and **Closed**. Only a Maintainer can close
a report.

## 3. First report walkthrough

Use fictional data only.

1. Select **New report**. Enter the title `Checkout total stays unchanged after
   removing an item`.
2. Under **What happened?** in the description template, type `the total is wrong
   after i remove something`.
3. Select **Check my report**. Wait up to a minute.
4. Read the questions. For example, the AI may ask which item you removed, what
   total you expected, and which browser you used.
5. Edit the description to answer them, for example by adding numbered steps and
   the expected total. Select **Preview** to check the formatting.
6. Select **Submit report**, then open the report from the list and check that it
   is **Submitted**.

## 4. Create a report

1. Select **New report**.
2. Enter a one-line **Title** (up to 200 characters).
3. Describe the problem in **Description** (up to 20,000 characters). The template
   suggests three headings: **What happened?**, **Steps to reproduce** and **What
   did you expect?**. Markdown lists, tables and code blocks are supported; use a
   fenced code block for error messages and logs.
4. Select **Preview** to check the formatting, then **Submit report**.

If submitting fails, your draft stays in the form. You can create up to 3 reports
per minute; if you reach the limit, wait for the time shown and try again.

## 5. Check my report (AI)

**Check my report** asks the team's AI service which parts of your draft are unclear
and what information a Developer would still need. It only asks questions. It never
changes your title or description, and nothing is saved until you select
**Submit report**.

1. Write your title and description on the **New report** screen.
2. Select **Check my report**. A progress message appears; a check usually takes
   10-35 seconds and stops after one minute.
3. Read the results panel, **Questions about your report**:
   - **Unclear parts** quotes words from your draft and asks a question about each
     one, for example *"the second one" → The second what: upload or page?*
   - **Questions by topic** asks about Summary, Steps to reproduce, Expected
     behavior, Actual behavior, Environment, Frequency, and Error messages and logs.
     Some questions ask for more detail on something you already wrote.
4. Edit your draft yourself to answer the questions that help, then select
   **Dismiss**, or run **Check my report** again.

Things to know:
- The check is available only on **New report**, not on submitted reports.
- You can run it **3 times in 5 minutes**. After that, the panel shows how many
  seconds to wait.
- Results are shown as plain text. Links and formatting in them are not active.
- The AI can be wrong. Answer only what is true, and ignore questions that do not
  apply.
- Results disappear when you submit, leave the page or start a new check.
- If the panel says *No questions found*, the AI found nothing to ask.
- Do not put passwords, personal information or real customer data in a draft.

## 6. Find and edit a report

The list shows only your reports. Use the title box and the **Open**, **Closed** and
**All** filters to narrow the current page, and the page controls for older
reports. The filters apply to the page that is loaded.

Open a report to edit its title or description. Editing is possible only while the
report is **Submitted** and not yet assigned. If the page says the report *can't be
edited any more*, ask a Maintainer to help. If it says the report *changed since you
opened it*, reload, review the current text, then edit again.

## 7. Add a comment

1. Open your report and scroll to **Add a comment**.
2. Write your message. Markdown is supported; use **Preview** to check it.
3. Submit and check that the comment appears in the timeline.

You can comment on closed reports. The Maintainer reads the discussion; ask the
Maintainer to pass on details a Developer needs.

## 8. Troubleshooting

| Situation | What to do |
| --- | --- |
| Sign-in fails | Check the username, password and that the account is a Reporter. Wait before retrying after several failures. |
| A report is missing from the list | Check the page, the Open/Closed/All filter and the title box. |
| **Check my report** says checking isn't available | The AI service is not configured or is down. You can still submit the report. |
| *You've checked too often* | Wait the number of seconds shown. |
| *Couldn't check your report* | The AI answer failed the safety checks. Try again, or submit without checking. |
| *The check took too long* | Try again later, or shorten very long logs in the draft. |
| A save or edit fails | Your text stays in the form. Refresh, review the report and retry. |

## 9. Current limitations

- **Check my report** works only on new drafts, not on submitted reports.
- The AI does not suggest a title or rewrite your text.
- The Reporter screen does not show the Developer's checklist and has no per-step
  responses.
- Report filters apply to the loaded page only.
- The AI model in local testing (`x-test-2`) is a provider test model that can
  change; question quality may vary.

## 10. Frequently asked questions

**Does the AI save or send my draft anywhere?** The draft goes to the team's AI
service for that one check. The application does not store it or the questions; only
**Submit report** saves a report.

**Why does "Questions by topic" ask about something I already wrote?** The AI also
asks follow-up questions when a topic could have more detail. Skip any that do not
help.

**Why did a question disappear between checks?** Each check is new, and the server
removes any question that contains a link, an email address or a request for a
password or other credential.

**Can I check a report after submitting it?** No. Edit it while it is Submitted and
unassigned, or add a comment.
