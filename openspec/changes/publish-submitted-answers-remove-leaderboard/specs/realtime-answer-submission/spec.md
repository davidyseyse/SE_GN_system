## MODIFIED Requirements

### Requirement: Students can submit one answer per active buzz round

The system SHALL allow the active buzz winner to submit one answer in the current round, prevent duplicate submissions in that round, and prevent duplicate client requests while the first request is unresolved.

#### Scenario: Student clicks submit once

- **WHEN** a student clicks the enabled submit-answer button
- **THEN** the client immediately disables that button without changing its visible label and sends exactly one submission request

#### Scenario: Student clicks while submission is unresolved

- **WHEN** the submission request has not yet succeeded or failed
- **THEN** the button remains non-interactive and no additional submission request is sent for that question and round

#### Scenario: Submission succeeds

- **WHEN** the server successfully persists the answer
- **THEN** the button remains disabled, the current answer is retained for display, and the visible status becomes `✓ 已作答`

#### Scenario: Submission fails

- **WHEN** the submission request fails
- **THEN** the client re-enables the button, preserves the answer draft, and reports the failure so the student can retry

#### Scenario: Duplicate submission reaches the server

- **WHEN** a second submission is attempted by the same student for the same question and internal round
- **THEN** the server rejects it and preserves the original submission

### Requirement: Realtime submission state is recoverable

The system SHALL use realtime submission-change notifications for immediate invalidation and authoritative answer reads for recovery.

#### Scenario: Connected clients receive submission notification

- **WHEN** a submission is persisted and its realtime notification is published
- **THEN** connected classroom clients automatically reconcile answers for the affected question

#### Scenario: Client state is recreated

- **WHEN** a submitted student's client reloads or reconnects
- **THEN** authoritative data restores the answer and submitted state instead of enabling an invalid second submission

