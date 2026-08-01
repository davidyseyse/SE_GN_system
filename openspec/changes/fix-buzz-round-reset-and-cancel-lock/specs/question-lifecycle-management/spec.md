## MODIFIED Requirements

### Requirement: Teachers can reset buzz questions

The system SHALL allow an authorized teacher or assistant to reset one buzz question or all buzz questions in a classroom, ending each affected current round without deleting its history.

#### Scenario: Teacher resets one active question

- **WHEN** a teacher resets a question in `BUZZ_OPEN`, `ANSWER_OPEN`, or `LOCKED`
- **THEN** the system ends its current round, releases its active lock, changes the question to `READY`, and publishes a reset event

#### Scenario: Reset does not immediately reopen buzzing

- **WHEN** a teacher resets an active question
- **THEN** students see the question as not open and cannot buzz until the teacher opens it again

#### Scenario: Teacher opens a reset question

- **WHEN** a teacher changes a reset buzz question from `READY` to `BUZZ_OPEN`
- **THEN** the system creates a new round and allows all eligible students to buzz and submit without being blocked by previous-round records

#### Scenario: Teacher resets all questions

- **WHEN** a teacher performs a classroom-wide reset
- **THEN** the system applies the same round-ending and `READY` transition to every affected question

