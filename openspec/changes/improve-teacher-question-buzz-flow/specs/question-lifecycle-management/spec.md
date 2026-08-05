## MODIFIED Requirements

### Requirement: Teacher can manage question banks
The system SHALL allow a teacher to create, edit, reorder, and archive questions for a classroom, including single-choice, multiple-choice, true-false, short-answer, and buzz-enabled questions, and every newly created question SHALL be immediately stored in `READY`.

#### Scenario: Teacher creates a question
- **WHEN** a teacher submits valid question content, question type, score, and answer settings
- **THEN** the system stores the question as `READY` and makes it available in the classroom question list without requiring a separate set-not-open operation

#### Scenario: Teacher reorders questions
- **WHEN** a teacher changes the order of questions in a classroom
- **THEN** the system persists the new order and returns the updated ordered list

#### Scenario: Existing draft question is encountered
- **WHEN** an existing pre-change question remains in `DRAFT`
- **THEN** the system provides a migration or compatibility path that allows the question to reach `READY` without restoring the removed teacher action

### Requirement: Question state transitions are enforced
The system SHALL enforce a valid question state machine across `READY`, `BUZZ_OPEN`, `ANSWER_OPEN`, `LOCKED`, `REVIEW`, `PUBLISHED`, and `CLOSED`, while retaining compatibility handling for legacy `DRAFT` data.

#### Scenario: Valid transition succeeds
- **WHEN** a teacher performs an operation that matches a legal state transition
- **THEN** the system changes the question state and publishes the new state to authorized classroom clients

#### Scenario: Invalid transition is rejected
- **WHEN** a teacher or student attempts an operation that is not allowed in the current question state
- **THEN** the system rejects the operation and returns a state validation error without changing the question

#### Scenario: Teacher completes a question for review
- **WHEN** a teacher selects complete-buzz-and-review for a question in `BUZZ_OPEN` or `ANSWER_OPEN`
- **THEN** the question becomes `LOCKED`, retains its current round data, rejects further buzzes and submissions, and is selected for answer review

#### Scenario: Teacher resets one question
- **WHEN** a teacher resets a question in `BUZZ_OPEN`, `ANSWER_OPEN`, or `LOCKED`
- **THEN** the system releases its current interaction locks, changes it to `READY`, publishes a reset event, and retains prior-round history

## REMOVED Requirements

### Requirement: Teacher manually sets a newly created question to not open
**Reason**: New questions now enter `READY` directly, so a separate `DRAFT` to `READY` teacher action is redundant and causes an unnecessary extra step.

**Migration**: Existing `DRAFT` records are converted or handled compatibly as `READY`; the teacher interface removes the “設為未開放” control and retains “重置本題”.
