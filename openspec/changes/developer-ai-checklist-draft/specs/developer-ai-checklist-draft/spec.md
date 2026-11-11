# Spec Delta

## Purpose

Provide safe, reviewable AI-generated investigation suggestions grounded in the currently assigned programming bug report.

## ADDED Requirements

### Requirement: AI-D01 Authorized advisory suggestions

An active, currently assigned Developer MAY request suggestions from the report's bounded title, description and saved private checklist draft. Other reports, unsaved editor changes, private notes and credentials SHALL be excluded. Generation SHALL NOT mutate domain state. Suggestions SHALL be shown separately and require explicit Developer action before applying them to the draft; Save and Share remain separate actions.

#### Scenario: Developer reviews a suggestion
- **WHEN** an assigned active Developer requests suggestions for a report
- **THEN** the system returns validated, bounded suggestions with their source version and leaves all persisted domain state unchanged
- **AND** suggestions are not saved or shared until the Developer explicitly applies and saves them

#### Scenario: Unauthorized request
- **WHEN** an unauthenticated, inactive, wrong-role, unrelated or former Developer requests suggestions
- **THEN** the request is denied without invoking the model or disclosing report content

### Requirement: Saved checklist context

Saved checklist context SHALL contain at most 20 steps and 8192 UTF-8 bytes in total. The system SHALL reject oversized input before provider invocation, treat the checklist as untrusted input, and instruct the provider to avoid duplicating existing steps.

#### Scenario: Oversized saved checklist
- **WHEN** the saved checklist exceeds its step or byte limit
- **THEN** generation is rejected before the provider is invoked

### Requirement: AI-D02 Stale and untrusted inputs

The system SHALL treat report text and model output as untrusted data. It SHALL validate output as a bounded list of plain-text steps and SHALL NOT execute instructions found in either. Before returning a generated result it SHALL recheck current account eligibility, assignment and report source version; a changed or inaccessible input SHALL cause the result to be discarded or rejected without returning suggestions.

#### Scenario: Assignment changes during generation
- **GIVEN** an assigned Developer starts generation
- **WHEN** the report is reassigned before generation completes
- **THEN** the former assignee receives no generated steps and no domain data is changed

#### Scenario: Report text attempts a state change
- **WHEN** report content instructs the model to close the report or reveal unrelated data
- **THEN** generation has no authority to perform that action and domain state remains unchanged

#### Scenario: Invalid model output
- **WHEN** the provider returns malformed, oversized or nonconforming output
- **THEN** the system returns a safe error without persisting or exposing provider internals

#### Scenario: Saved draft changes during generation or review
- **WHEN** the saved draft changes after generation begins or before suggestions are applied
- **THEN** those suggestions are rejected as stale and are not applied; the response carries the saved draft revision used for generation

### Requirement: AI-D03 Bounded and observable provider use

The system SHALL enforce request size, output size, deadlines and concurrency controls; it SHALL return safe errors when the provider fails or limits are reached. It SHALL record sanitized usage, latency, outcome, selected model and configured limits when available, without logging credentials, report content or provider response text. Production use SHALL follow the team's shared gateway and confirmed quota policy.

#### Scenario: Developer model override
- **GIVEN** a nonblank server-side `SOC_LLM_MODEL_DEVELOPER` is configured
- **WHEN** the Developer gateway and suggestion provider are created
- **THEN** both use that model, while other roles retain their shared model
- **AND** an absent or blank Developer override falls back to `SOC_LLM_MODEL`

#### Scenario: Provider times out
- **WHEN** the configured deadline expires
- **THEN** the request returns a safe failure and no report or checklist mutation occurs

#### Scenario: Usage fields are absent
- **WHEN** a provider response omits token metrics
- **THEN** the metrics are recorded as unavailable rather than estimated
