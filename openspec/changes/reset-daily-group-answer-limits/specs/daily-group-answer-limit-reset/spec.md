## ADDED Requirements

### Requirement: Authorized staff can reset all groups' current-day answer-limit usage

The system SHALL allow an authenticated teacher or assistant to reset the successful-answer usage of every group number for the current Asia/Taipei calendar day. The operation SHALL apply system-wide across all classrooms and SHALL restore each group to zero used successes under the fixed limit of one.

#### Scenario: Teacher resets current-day usage

- **WHEN** an authenticated teacher confirms the answer-limit reset
- **THEN** the system removes every global group success counter for the current Taipei date
- **AND** every group is eligible to complete one subsequent successful answer that day
- **AND** the response reports how many current-day group counters were reset

#### Scenario: Assistant resets current-day usage

- **WHEN** an authenticated assistant confirms the answer-limit reset
- **THEN** the system performs the same system-wide current-day reset

#### Scenario: No group has current-day usage

- **WHEN** an authorized reset is requested while no current-Taipei-date counter exists
- **THEN** the operation succeeds with zero counters reset

#### Scenario: Student requests a reset

- **WHEN** a student or unauthenticated caller requests an answer-limit reset
- **THEN** the system rejects the request
- **AND** no group counter is changed

### Requirement: Answer-limit reset preserves classroom and answer history

The answer-limit reset SHALL NOT change question lifecycle state, buzz rounds, buzz attempts, submissions, scores, prior-day counters, or external Google Sheets content. It SHALL NOT change the fixed successful-answer limit of one.

#### Scenario: Reset follows successful answers

- **WHEN** one or more groups have retained successful submissions and an authorized user resets the answer limit
- **THEN** those submissions, scores, and buzz history remain unchanged
- **AND** only the groups' current-day limit usage returns to zero

#### Scenario: Prior-day counters exist

- **WHEN** the reset occurs while counters exist for earlier Taipei dates
- **THEN** all earlier-date counters remain unchanged

#### Scenario: Questions are active

- **WHEN** the reset occurs while one or more questions are open, stopped, or locked
- **THEN** every question and round retains its existing state

### Requirement: Teacher dashboard exposes a separate reset control

The teacher dashboard SHALL display a `重置答題限制` button beside `重置全部題目`. Activating it SHALL require confirmation with the exact message `確定要重置所有組別的答題限制嗎？` and SHALL perform no mutation when confirmation is declined.

#### Scenario: Teacher confirms the reset

- **WHEN** the teacher selects `重置答題限制` and confirms the dialog
- **THEN** the dashboard requests the authorized answer-limit reset
- **AND** it reloads the classroom member data after success
- **AND** the member list reflects the server's current `groupSuccessfulBuzzCount` values

#### Scenario: Teacher cancels confirmation

- **WHEN** the teacher selects `重置答題限制` and declines the dialog
- **THEN** the dashboard sends no reset request
- **AND** displayed usage remains unchanged

#### Scenario: Reset removes current-day counters

- **WHEN** the reset succeeds without a concurrent subsequent answer
- **THEN** each displayed member shows their group usage as `0 / 1`
- **AND** the dashboard reports that all groups' current-day answer limits were reset

