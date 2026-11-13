# Spec Delta

## Purpose

Lets a Reporter writing a new report ask the SoC LLM which parts of the draft are
unclear and what information is missing, without the AI changing the draft.

## ADDED Requirements

### Requirement: RC-01 On-demand check for new reports only

The New Report screen SHALL offer a Check my report action that calls the model only
when the Reporter requests it. No other screen SHALL offer it. A check SHALL NOT
create, change or store a report, and SHALL NOT store the draft or the results on the
server.

#### Scenario: RC-01a Reporter requests a check

- **WHEN** a Reporter on the New Report screen with a nonblank description chooses Check my report
- **THEN** one check request is sent and its results are shown
- **AND** the Reporter's report count and all stored report data are unchanged

#### Scenario: RC-01b Not offered elsewhere

- **WHEN** a Reporter views or edits an existing report
- **THEN** no Check my report action is shown

#### Scenario: RC-01c Never automatic

- **WHEN** the Reporter types, previews or submits without choosing Check my report
- **THEN** no check request is sent

### Requirement: RC-02 Only active Reporters

The server SHALL accept a check only from an authenticated, active Reporter session
with a valid CSRF token. Any other caller SHALL be refused before the model is
called, with a safe error that contains none of the submitted text.

#### Scenario: RC-02a Wrong caller

- **WHEN** an unauthenticated caller, an inactive Reporter, a Developer or a Maintainer sends a check request
- **THEN** the request is refused, the model is not called and no admission is used

#### Scenario: RC-02b Missing CSRF token

- **WHEN** an active Reporter sends a check request without a valid CSRF token
- **THEN** the request is refused and the model is not called

### Requirement: RC-03 Request bounds

A request SHALL contain exactly the draft `title` and `description`. They SHALL meet
the same text rules and limits as report creation (title 1-200 characters on one
line, description 1-20,000 characters, no NUL or lone surrogates). Invalid or
oversized input SHALL be refused before the model is called.

#### Scenario: RC-03a Oversized description

- **WHEN** the description is 20,001 characters
- **THEN** the request is refused with a validation error and the model is not called

#### Scenario: RC-03b Extra fields

- **WHEN** the request body includes any key other than `title` and `description`
- **THEN** the request is refused and the model is not called

#### Scenario: RC-03c Full-length draft accepted

- **WHEN** the description is exactly 20,000 characters of three-byte UTF-8 text
- **THEN** the request is within the Reporter task policy and is sent to the model

### Requirement: RC-04 Unclear parts quote the draft exactly

Each unclear part SHALL have a quote of 1-300 characters that appears exactly in the
draft title or description, and one question. A result SHALL have at most 8 unclear
parts with no duplicate quotes. Model output with a quote that is not an exact
substring of the draft SHALL be rejected as invalid.

#### Scenario: RC-04a Valid unclear part

- **WHEN** the model returns a quote that appears exactly in the description, with a question
- **THEN** the result shows that quote and its question

#### Scenario: RC-04b Invented quote rejected

- **WHEN** the model returns a quote that does not appear exactly in the title or description
- **THEN** the whole result is rejected with a safe "couldn't check your report" error

### Requirement: RC-05 Missing information uses fixed categories

Each missing item SHALL name one category from: Summary, Steps to reproduce,
Expected behavior, Actual behavior, Environment, Frequency, Error messages and
logs. Each category SHALL appear at most once, with one question. Unknown or
duplicate categories, extra keys, wrong types or questions outside 1-300 characters
on one line SHALL make the result invalid.

#### Scenario: RC-05a Missing environment

- **WHEN** the draft says nothing about the environment and the model returns Environment with a question
- **THEN** the result lists Environment and its question

#### Scenario: RC-05b Unknown category rejected

- **WHEN** the model returns a category that is not on the list, or the same category twice
- **THEN** the whole result is rejected with a safe error

#### Scenario: RC-05c Nothing to ask

- **WHEN** the model returns no unclear parts and no missing items
- **THEN** the Reporter sees a message that no questions were found

### Requirement: RC-06 The draft is never changed

Showing or dismissing results SHALL NOT change the title or description. Results
SHALL be shown as plain text, never as rendered Markdown, HTML or links. Results
SHALL be cleared when the Reporter submits, leaves the page or starts a new check.

#### Scenario: RC-06a Draft unchanged

- **WHEN** results are shown and then dismissed
- **THEN** the title and description are exactly as they were before the check

#### Scenario: RC-06b Markup in output shown as text

- **WHEN** a question contains Markdown, HTML or a URL
- **THEN** it is shown as literal text with no link or element created

### Requirement: RC-07 Untrusted input

The draft title and description SHALL be treated as untrusted data in a message
separate from the trusted instructions. Instructions inside the draft SHALL NOT be
followed. Output is accepted only if it passes RC-04 and RC-05.

#### Scenario: RC-07a Injection in draft

- **WHEN** the draft tells the model to ignore its rules, output other text or add links
- **THEN** any accepted result still contains only valid unclear parts and missing items, shown as plain text

#### Scenario: RC-07b Questions screened

- **WHEN** a question contains a URL (`http://`, `https://` or `www.`), an email address, or asks for a password, API key, token or other credential
- **THEN** that unclear part or missing item is dropped and the remaining valid items are returned
- **AND** if every item was dropped, the check fails with a safe "couldn't check your report" error rather than reporting that nothing is unclear

#### Scenario: RC-07c Hidden characters

- **WHEN** a question contains invisible or default-ignorable characters, direction marks or controls, or shows no visible character
- **THEN** the whole result is rejected as invalid output, and screening also ignores such characters so they cannot hide a screened word

### Requirement: RC-08 Bounded provider use and safe failures

Requests SHALL use the `REPORTER_ASSIST` task policy and the shared per-key budgets. Timeout, rate limit, busy, unavailable and invalid output
SHALL return safe error codes with the draft unchanged. Logs SHALL NOT contain the
draft text, quotes or questions.

#### Scenario: RC-08a Rate limited

- **WHEN** the Reporter exceeds the task's starts per window
- **THEN** a rate-limited message with a retry time is shown and the draft is unchanged

#### Scenario: RC-08b Live AI disabled

- **WHEN** live AI is disabled or the Reporter key is missing
- **THEN** Check my report is unavailable with a safe message, and report creation still works

### Requirement: RC-09 Reporter model setting

The Reporter task SHALL use `SOC_LLM_MODEL_REPORTER` when it is set to a nonblank
value, and `SOC_LLM_MODEL` otherwise. Gateway requests for every role SHALL NOT include
`reasoning_effort`, so the provider default applies.

#### Scenario: RC-09a Reporter model override

- **WHEN** `SOC_LLM_MODEL_REPORTER` is set
- **THEN** Reporter calls use that model while other roles keep their configured models

#### Scenario: RC-09b Blank override falls back

- **WHEN** `SOC_LLM_MODEL_REPORTER` is missing or blank
- **THEN** Reporter calls use `SOC_LLM_MODEL`

#### Scenario: RC-09c No reasoning field

- **WHEN** any role's call is sent to the provider
- **THEN** the request body has no `reasoning_effort` key
