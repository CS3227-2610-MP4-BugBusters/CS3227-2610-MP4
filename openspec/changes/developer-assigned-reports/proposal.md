# Developer assigned reports

## Why

Developers need a reliable view of their current assignments before they can investigate a report. The team has not yet standardized the dashboard's visual template.

## What changes

- Define a server-authorized list of currently assigned reports and its minimal fields.
- Define title or ID search, status filtering, pagination, and empty-result behavior.
- Define a Developer-owner API contract independent of visual layout.

## Scope and dependencies

This is a local implementation contract, not an implementation. It depends on shared authentication, persisted reports, current assignment, and agreed status names. Priority, status counts, report details, workflow actions, checklist integration, and the shared visual template are separate follow-up decisions. Closed reports remain visible only while still assigned. The route and fields must be reconciled with the shared API and schema during integration.
