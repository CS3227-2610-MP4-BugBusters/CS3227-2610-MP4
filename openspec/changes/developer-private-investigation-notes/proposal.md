# Developer private investigation notes

## Why

A Developer needs a private working area alongside the checklist. When a report is reassigned, the next assigned Developer needs the investigation context without exposing it to the Reporter or Maintainer.

## What changes

- Provide one editable plain-text private notes field per report, including a way to clear it.
- Restrict reads and changes to the currently assigned active Developer; preserve notes through reassignment.
- Use an independent notes revision to reject stale changes. Private notes do not affect a pending resolution proposal or report version.

## Scope and dependencies

This is a specification, not an implementation. It requires shared report persistence, a trusted active-user session, current assignment, a report detail screen and the agreed report-first transaction boundary. The merged Maintainer change already reflects CL-05 for private checklist Save and Share. Its investigation-note wording and Maintainer report projection still need reconciliation for the newly agreed private Developer notes before implementation. This change does not silently change the Maintainer's specification. Notifications, shared checklist publishing, AI-generated notes, attachments and report-field editing are outside scope.
