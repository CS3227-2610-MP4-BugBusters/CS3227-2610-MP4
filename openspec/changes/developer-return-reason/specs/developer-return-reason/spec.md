# Developer return reason

## ADDED Requirements

### Requirement: Assigned Developer can see the latest return reason

The Developer report detail SHALL expose the latest recorded Maintainer resolution return reason for an in-progress report only to the currently assigned active Developer. It SHALL exclude unrelated history and render the reason as plain text.

#### Scenario: Returned proposal
- **GIVEN** an assigned Developer's in-progress report has a recorded resolution return reason
- **WHEN** the Developer opens the report
- **THEN** the latest reason is shown with a clear label

#### Scenario: Former assignee
- **WHEN** a former or unrelated Developer requests the report detail
- **THEN** access is denied and the reason is not disclosed

#### Scenario: No applicable return
- **WHEN** there is no return event or the report is not in progress
- **THEN** no return reason is shown
