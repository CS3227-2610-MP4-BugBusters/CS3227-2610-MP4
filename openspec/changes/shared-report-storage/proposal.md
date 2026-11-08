# Shared report storage

## Why

Reporter, Maintainer, and Developer features require one persistent report record. Existing Developer tests use isolated fixtures, not a shared production migration.

## What changes

- Establish a minimum PostgreSQL report table shared by the three roles and compatible with the existing checklist migration and Developer list.
- Define optimistic report-version checks for future mutating services and verify the database supports them.
- Provide a reproducible fresh-database migration and integration verification.

## Scope

The migration covers core report identity, title, description, reporter, current assignee, status, version and proposal reference. Authentication, identity and proposal tables, API endpoints, role authorization, full lifecycle mutations and deployment are separate dependencies. Priority, detailed reproduction fields and other Maintainer DTO fields require later shared decisions. An existing database upgrade needs a separately designed migration; this change targets a fresh database only.
