## ADDED Requirements

### Requirement: DR-01 Current assignment list

The system SHALL return only reports currently assigned to the authenticated active Developer, with report ID, title and status. It SHALL include all statuses for reports still assigned, including closed reports. The server SHALL derive the Developer identity from the session and SHALL NOT trust a client-supplied identity.

#### Scenario: List after reassignment
- **GIVEN** report R is assigned to D1 and then reassigned to D2
- **WHEN** each Developer requests their list after reassignment commits
- **THEN** R is absent from D1's result and present in D2's result

#### Scenario: Unauthenticated or wrong-role request
- **WHEN** an unauthenticated user or a user without an active Developer account requests the list
- **THEN** the server denies the request and returns no report data

### Requirement: DR-02 Search and filter assignments

The system SHALL support case-insensitive literal title substring search or exact complete ID search and status filtering. It SHALL apply both conditions together to the authorized set. Whitespace-only search SHALL be treated as no search.

#### Scenario: Combined search and status filter
- **GIVEN** the Developer has assigned reports with different titles and statuses and another Developer has a matching report
- **WHEN** the Developer searches a title fragment and selects a status
- **THEN** only their own reports matching both conditions are returned

#### Scenario: Search by report ID
- **GIVEN** an assigned report has a known complete ID
- **WHEN** the Developer searches that ID
- **THEN** the matching report is returned subject to any status filter

### Requirement: DR-03 Stable pagination and result states

The system SHALL order results by report ID ascending and paginate the authorized filtered set. The response SHALL include the requested page, page size and filtered total. Invalid pagination or status SHALL be rejected. The dashboard SHALL distinguish empty assignments, no filter matches, an out-of-range page, loading and errors.

#### Scenario: No matching reports
- **GIVEN** a Developer has assigned reports
- **WHEN** a valid filter matches none
- **THEN** the total is zero and the dashboard presents a no-matches state

#### Scenario: Requested page beyond the end
- **GIVEN** the filtered total is nonzero
- **WHEN** the Developer requests a page beyond the last page
- **THEN** the items are empty and the total remains nonzero

### Requirement: DR-04 Identity-scoped dashboard state

The dashboard SHALL clear a Developer's search, filters, pagination and report selection when access ends or another Developer signs in. It SHALL NOT replay the previous Developer's query under the new session.

#### Scenario: Sign out and sign in as another Developer
- **GIVEN** Developer D1 has entered or submitted a report search, selected a status or navigated to another page
- **WHEN** D1 signs out and Developer D2 signs in in the same application instance
- **THEN** the search input, filters, page and selected report are cleared
- **AND** the application requests D2's reports without D1's previous query parameters
