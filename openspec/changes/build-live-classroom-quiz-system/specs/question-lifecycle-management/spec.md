## ADDED Requirements

### Requirement: Teacher can manage question banks
The system SHALL allow a teacher to create, edit, reorder, and archive questions for a classroom, including single-choice, multiple-choice, true-false, short-answer, and buzz-enabled questions.

#### Scenario: Teacher creates a question
- **WHEN** a teacher submits valid question content, question type, score, and answer settings
- **THEN** the system stores the question and makes it available in the classroom question list

#### Scenario: Teacher reorders questions
- **WHEN** a teacher changes the order of questions in a classroom
- **THEN** the system persists the new order and returns the updated ordered list

### Requirement: Question state transitions are enforced
The system SHALL enforce a valid question state machine across `DRAFT`, `READY`, `BUZZ_OPEN`, `ANSWER_OPEN`, `LOCKED`, `REVIEW`, `PUBLISHED`, and `CLOSED`.

#### Scenario: Valid transition succeeds
- **WHEN** a teacher performs an operation that matches a legal state transition
- **THEN** the system changes the question state and publishes the new state to authorized classroom clients

#### Scenario: Invalid transition is rejected
- **WHEN** a teacher or student attempts an operation that is not allowed in the current question state
- **THEN** the system rejects the operation and returns a state validation error without changing the question

### Requirement: Students only see active question content
The system SHALL expose only the currently active question and only the fields permitted by the teacher's publication settings.

#### Scenario: Hidden answer remains concealed
- **WHEN** a question is open for answering but answer publication is disabled
- **THEN** the system sends question content to students without exposing the correct answer

#### Scenario: Published answer becomes visible
- **WHEN** a teacher publishes a question after review
- **THEN** the system exposes the correct answer and score outcome according to publication settings
