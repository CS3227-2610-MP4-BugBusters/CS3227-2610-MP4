# Shared frontend design for the bug report tracker

**Status:** Proposed for team agreement. The wireframes describe the intended
experience; they do not claim that every screen or action is implemented.
The Developer owner coordinates the shared design. Each role owner confirms
their role's fields and implements their own workflows.

## Design direction

Use a simple, responsive workspace with the same header, typography, controls
and report presentation for all three roles. A signed-in user has one role and
sees navigation and actions available to that role. The server remains responsible
for authorization. Start from the existing Developer and Maintainer React apps;
agree on the shared code location and package boundaries before extracting common
components. The Reporter owner's existing or planned frontend needs checking.

### Proposed styling

Use a dark blue-green sidebar, light neutral page
background, white content surfaces, subtle borders and one blue-green primary
action colour. Use a system sans-serif font, approximately 24–28 px page titles,
16 px body text, 8 px spacing increments and modestly rounded cards. Keep status
counts compact and show them only when available from an authorized API; they
are not required before the report list can be used. The team can adjust the
palette and typography during review.

## Shared shell

```text
+------------------------------------------------------------------+
| Bug Tracker                 [role]  [display name]  [Sign out]   |
+------------------------------------------------------------------+
| Role navigation             | Page title                         |
| - Dashboard                 | Brief page description             |
| - Role-specific pages       |                                    |
|                             | [status] [status] [status]         |
|                             | Search and filters                 |
|                             | Report list / selected detail      |
+------------------------------------------------------------------+
```

On small screens, place navigation above the content and stack panels vertically.
The header and navigation do not display protected reports until the session and
role have been checked. Sign-out or access denial clears protected content.

## Dashboard wireframes

### Reporter — proposed; Reporter owner confirms fields and actions

```text
| My reports                                      [New report] |
| Search [____________]  Status [All v]                      |
| ID       Title                    Status         Updated     |
| #123     Export omits final row   In progress    Today       |
| [Previous]                                      [Next]       |
| Select a report to view its details and shared checklist.    |
```

Report creation and editing use Reporter-owned field and visibility rules. Show
the AI report-improvement suggestion separately, with an explicit action to apply
it to the editable text; the Reporter reviews and submits the report. Show only
the published checklist and the Reporter's authorized response controls.

### Developer — agreed journey; notifications need a separate contract

```text
| Assigned reports                                             |
| Notifications: assigned / removed / returned / reopened     |
| Search [____________]  Status [All v]                       |
| ID       Title                    Status         Updated     |
| #123     Export omits final row   Assigned       Today       |
| [Previous]                                      [Next]       |
| Selected report: detail | private notes | checklist          |
```

Show private notes only to the currently assigned Developer. Checklist Save is
private; Share publishes the saved nonempty draft. Display AI suggestions in a
separate preview with explicit Apply, then separate Save and Share actions.
Notifications after access loss must not reveal report content. Notification
delivery and persistence remain separate work. A reopening notification goes
to the previously assigned Developer and does not restore access.

Planned investigation actions include starting an assigned investigation and
proposing a resolution with required text. Preserve unsent resolution text on
stale writes; show a return reason and allow a new proposal. Withdraw and revise
depends on lifecycle agreement. Closed reports remain read-only while assigned.

### Maintainer — based on Maintainer specifications

```text
| Report queue                                  [AI summary]   |
| Status [All v]  Priority [All v]  Assignee [All v]          |
| ID       Title                    Status        Assignee     |
| #123     Export omits final row   Submitted     Unassigned   |
| [Previous]                                      [Next]       |
| Selected report: details | shared evidence | history        |
```

Triage and assignment, resolution review, return, close and reopen controls
appear only when allowed by the agreed lifecycle and the user's permissions.
The planned SoC summary is advisory, grounded in the authorized unresolved queue,
and subject to the Maintainer's context and resource controls.
Maintainers do not see Developer private notes.

## Common components and states

| Component | Proposed convention |
| --- | --- |
| Page header | Descriptive title; one prominent primary action where appropriate. |
| Report list | ID, title, status and relevant role-specific fields; selection opens details. Search/filter controls have labels. |
| Status badge | Text label plus colour; never rely on colour alone. Use the agreed lifecycle names. |
| Forms | Visible labels, clear required fields and inline validation. Preserve unsent text after errors or stale writes. |
| Actions | Primary, secondary and destructive styles; disable while the specific request is pending. Confirm destructive actions when the applicable feature contract requires it. |
| AI output | Label as AI-generated and advisory; show source/version when applicable. Review and apply explicitly. Never automatically save, share or change status. |
| Feedback | Distinguish loading, empty, no matches, access denied, stale data and network failure. Provide a retry where useful; do not expose raw server errors. |

Use semantic headings, labelled controls, keyboard operation, visible focus,
readable contrast and status announcements. Render report and AI text as text,
not raw HTML. Dates use a consistent display with a clear timezone. API and
permission checks remain on the server; the frontend handles denied and stale
responses without exposing previously loaded protected data.

## Delivery and acceptance

1. Role owners review their wireframes and confirm navigation, report fields and
   actions. Agree on the common component location and visual tokens.
2. Use shared authentication as the prerequisite for further role UI integration.
   The Developer owner designs shared frontend and API handling. Specify the shell
   and component interfaces in an appropriately scoped OpenSpec change before
   product code; adopt it incrementally while preserving existing behavior.
3. Each role owner connects their screens to the shared shell and their own API.
   Reviewer checks role access, responsive layouts, keyboard navigation and the
   loading, empty, error and stale states for each screen.
4. Verify the shared shell with all three roles and record which screens use real
   services versus synthetic data. Check the final deployed revision separately.

## Team decisions still needed

- Reporter owner: report fields, edit rules, response controls and dashboard navigation.
- All owners: shared package location, visual tokens, status labels and timezone display.
- Maintainer and Developer owners: which lifecycle actions are ready to expose.
- All owners: notification contract and whether Developer AI response assessment is in the release.
