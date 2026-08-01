## Purpose

讓授權教師以一次課堂範圍操作開放、停止或重置多個搶答題目，同時維持每題回合的一致性並清楚回報處理結果。

## ADDED Requirements

### Requirement: Teachers can open all eligible buzz questions
The system SHALL allow an authorized teacher or assistant to open every eligible `READY` buzz question in the current classroom with one operation.

#### Scenario: Classroom contains multiple ready questions
- **WHEN** a teacher invokes open-all and multiple buzz questions are `READY`
- **THEN** each eligible question becomes `BUZZ_OPEN`, starts exactly one new round, and publishes its new state and round identifier

#### Scenario: Some questions are not eligible to open
- **WHEN** open-all encounters questions that are already open or are `LOCKED`, `REVIEW`, `PUBLISHED`, or `CLOSED`
- **THEN** the system does not reopen or advance those questions and reports them as unchanged or skipped with a reason

### Requirement: Teachers can stop all active questions without resetting rounds
The system SHALL allow an authorized teacher or assistant to stop every `BUZZ_OPEN` or `ANSWER_OPEN` question in the current classroom with one operation.

#### Scenario: Teacher stops all active questions
- **WHEN** a teacher invokes stop-all while one or more questions are accepting buzzes or answers
- **THEN** each active question becomes `LOCKED`, rejects subsequent buzzes and submissions, retains its current round, winner and submissions, and publishes its stopped state

#### Scenario: Stop-all encounters inactive questions
- **WHEN** stop-all encounters a `READY`, `LOCKED`, `REVIEW`, `PUBLISHED`, or `CLOSED` question
- **THEN** the question remains unchanged and its round is not created, reset, or advanced

### Requirement: Teachers can reset all affected questions
The system SHALL allow an authorized teacher or assistant to reset all affected buzz questions in the current classroom with one operation.

#### Scenario: Teacher resets all questions
- **WHEN** a teacher invokes reset-all
- **THEN** every affected active or locked question ends its current interaction, releases its active lock, becomes `READY`, publishes a reset event, and preserves historical buzz and submission records

#### Scenario: Reset-all encounters a finalized question
- **WHEN** reset-all encounters a question in `REVIEW`, `PUBLISHED`, or `CLOSED`
- **THEN** the system leaves that question unchanged and reports that it was skipped

### Requirement: Batch operations return per-question outcomes
Each classroom batch operation MUST return an outcome for every question considered, including the question identifier, previous state, resulting state, action result, round identifier when applicable, and a reason for any skip or failure.

#### Scenario: Batch operation has mixed outcomes
- **WHEN** eligible, ineligible, and failing questions are encountered in one batch request
- **THEN** the response distinguishes changed, unchanged, skipped, and failed questions without falsely reporting complete success

#### Scenario: Unauthorized user invokes a batch operation
- **WHEN** a user without teacher or assistant authority invokes a classroom batch operation
- **THEN** the system rejects the entire request without changing any question or round
