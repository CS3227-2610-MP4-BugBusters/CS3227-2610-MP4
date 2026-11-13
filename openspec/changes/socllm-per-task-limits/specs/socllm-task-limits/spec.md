# Spec Delta

## Purpose

Defines the server-owned limits that apply to each SoCLaaS gateway task, so each
role's AI calls can be bounded separately while the provider-key budgets stay shared.

## ADDED Requirements

### Requirement: AI-TASK-POLICY Every task has a server-owned policy

The gateway SHALL hold one limit policy for each registered task (Reporter assist,
Developer assist, Maintainer queue summary). A policy SHALL set max input bytes,
max instruction bytes, max output tokens, max deadline, starts allowed per account
in a rolling window, the window length, and max in-flight calls per account. All
values SHALL be positive. Callers and request data SHALL NOT be able to choose or
change a policy.

#### Scenario: TL-01 Every task has a policy

- **WHEN** the gateway starts
- **THEN** each of the three tasks has exactly one policy with all values positive

#### Scenario: TL-02 Invalid policy rejected

- **WHEN** a policy is built with a zero or negative value, a deadline over 60 seconds, a window over 24 hours, or input/instruction/output limits above the transport ceilings (61,440 / 8,192 bytes, 8,192 tokens; raised by `reporter-report-clarify`)
- **THEN** construction fails and no gateway service using it is created

### Requirement: AI-TASK-INITIAL Initial values preserve current limits

Developer assist and Maintainer queue summary SHALL use these values: 24,576 input
bytes, 8,192 instruction bytes, 2,048 output tokens, 30-second max deadline, 3
starts per account per rolling 5 minutes, and 1 in-flight call per account. Reporter
assist has its own policy, defined by `reporter-report-clarify`.

#### Scenario: TL-03 Initial values

- **WHEN** the policies for Developer assist and Maintainer queue summary are read
- **THEN** each one has exactly the initial values above

### Requirement: AI-TASK-CALL-BOUNDS Calls are checked against their task's policy

Before admission or any provider request, the gateway SHALL reject a call whose
input bytes, instruction bytes, requested output tokens or deadline exceed its own
task's policy. A rejected call SHALL use no admission slot, reserve no tokens and
make no provider request. The error SHALL be safe and SHALL NOT include the content.

#### Scenario: TL-04 Over the task's input limit

- **WHEN** a call's UTF-8 input is one byte over its task's max input bytes
- **THEN** the call is rejected with a safe error, nothing is admitted and the provider is not called

#### Scenario: TL-05 At the task's limits

- **WHEN** a call's input, instructions, output tokens and deadline are exactly at its task's limits
- **THEN** the call is admitted, assuming key budgets allow it

#### Scenario: TL-06 Limits come from the call's own task

- **WHEN** two tasks have different policies and a call fits one task's limits but not its own task's
- **THEN** the call is rejected according to its own task's policy

### Requirement: AI-TASK-ADMISSION Per-account limits are counted per task

Durable admission SHALL count starts and in-flight calls per account per task,
using that task's start limit, window and in-flight limit. Exceeding the start
limit SHALL return a rate-limited error with Retry-After equal to the window in
seconds. Exceeding the in-flight limit SHALL return a busy error.

#### Scenario: TL-07 Start limit per task

- **WHEN** an account has used its task's allowed starts within the window
- **THEN** the next start for that task is rate-limited with Retry-After equal to the window

#### Scenario: TL-08 Separate tasks counted separately

- **WHEN** one account has used all starts for one task
- **THEN** a start for a different task is not refused because of that task's count

#### Scenario: TL-09 In-flight limit per task

- **WHEN** an account has its task's max in-flight calls running
- **THEN** another call for the same task is refused as busy

### Requirement: AI-TASK-KEY-BUDGET Key budgets stay shared and unchanged

Per-task limits SHALL add to, not replace, the per-key limits: requests and
reserved tokens per minute, the 4-call concurrency cap, and the strictest budget
for keys shared by several roles. A call SHALL be admitted only if both its task
policy and its key budget allow it.

#### Scenario: TL-10 Key budget still applies

- **WHEN** a call is within its task policy but its key has reached its per-minute request budget
- **THEN** the call is rate-limited by the key budget

#### Scenario: TL-11 Maintainer reservation uses its policy

- **WHEN** a Maintainer summary is admitted
- **THEN** its token reservation equals its policy's max instruction bytes + max input bytes + max output tokens
