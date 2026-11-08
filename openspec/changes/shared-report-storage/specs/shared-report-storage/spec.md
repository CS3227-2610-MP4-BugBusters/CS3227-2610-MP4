## ADDED Requirements

### Requirement: SR-01 Shared report record

The system SHALL provide one PostgreSQL reports record with UUID identity, title, description, Reporter identity, optional Developer assignment, status, nonnegative version, and optional current proposal reference. The database SHALL reject invalid status values, missing required fields and negative versions.

#### Scenario: New unassigned report
- **GIVEN** a fresh database migrated for shared reports
- **WHEN** a report is inserted with an ID, title, description and Reporter ID
- **THEN** it is unassigned, has status `SUBMITTED`, version zero and no current proposal

#### Scenario: Invalid report state
- **WHEN** a report with an unsupported status or negative version is inserted or updated
- **THEN** the database rejects the change

### Requirement: SR-02 Optimistic report version

A report mutation SHALL compare the expected version and increment the version atomically. A stale update SHALL not overwrite a committed change. This requirement applies to services that mutate reports; the schema by itself SHALL NOT be represented as enforcing every caller's behavior.

#### Scenario: Competing report updates
- **GIVEN** two writers read the same report version
- **WHEN** both attempt version-checked updates and one commits first
- **THEN** the other updates no report and the first writer's data remains

### Requirement: SR-03 Checklist migration compatibility

The shared reports migration SHALL apply before the existing checklist migration on a fresh PostgreSQL database and permit a checklist for a persisted report.

#### Scenario: Persist checklist against a report
- **GIVEN** a fresh database has applied both migrations in order
- **WHEN** a report and its checklist are stored
- **THEN** both records can be read back with the checklist referencing that report
