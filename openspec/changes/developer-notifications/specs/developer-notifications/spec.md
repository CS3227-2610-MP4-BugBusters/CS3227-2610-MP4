# Developer notifications

## ADDED Requirements

### Requirement: Persist lifecycle notifications atomically

The system SHALL create a minimal Developer notification in the same transaction as an assignment, reassignment, resolution return or reopening. It SHALL not create notifications for failed or rolled-back transitions.

#### Scenario: Assignment and reassignment
- **WHEN** a report is assigned or reassigned
- **THEN** the new Developer receives an assignment notification and any former Developer receives a reassignment notification

#### Scenario: Resolution return and reopening
- **WHEN** a resolution is returned or a closed report is reopened
- **THEN** the current or previously assigned Developer respectively receives a notification

### Requirement: Protect notification and report access

Only an active authenticated Developer SHALL list or mark their own notifications read. Notifications SHALL not contain report content and SHALL NOT grant report access.

#### Scenario: Former assignee
- **WHEN** a Developer is no longer assigned
- **THEN** their notification has a generic message and no report link or identifier, and direct report access remains denied

#### Scenario: Other user's notification
- **WHEN** a Developer tries to mark another Developer's notification read
- **THEN** the operation is denied without changing it or disclosing its content

### Requirement: Show recent notifications

The Developer dashboard SHALL show up to 50 recent notifications and allow owned notifications to be marked read. Refreshing the dashboard SHALL retrieve the current list.

#### Scenario: Read acknowledgement
- **WHEN** a Developer marks their own notification read
- **THEN** it is displayed as read and repeated acknowledgement is safe
