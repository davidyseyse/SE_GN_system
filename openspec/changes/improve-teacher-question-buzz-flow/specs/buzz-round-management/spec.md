## MODIFIED Requirements

### Requirement: Buzz attempts are isolated by round
The system SHALL associate every buzz attempt, active winner, and answer submission for a buzz question with a persistent buzz round, and SHALL create a new round only when an eligible `READY` question is opened.

#### Scenario: Teacher reopens a reset question
- **WHEN** a teacher opens a buzz question after resetting its previous round
- **THEN** the system creates exactly one new round whose winner and submissions are independent of all previous rounds

#### Scenario: Batch open starts independent rounds
- **WHEN** a teacher opens multiple eligible questions with one batch operation
- **THEN** the system creates exactly one new current round for each opened question

#### Scenario: Batch open skips an ineligible question
- **WHEN** a batch open encounters a question that is not eligible to open
- **THEN** the system does not create or advance a round for that question

#### Scenario: Historical rounds remain auditable
- **WHEN** a new round is created for a question
- **THEN** the system preserves previous-round buzz attempts, winners, cancellations, and submissions as historical records

### Requirement: Stopping a round preserves current-round data
The system MUST distinguish stopping a question from resetting it.

#### Scenario: Teacher stops an active question
- **WHEN** a teacher changes an active question to `LOCKED`
- **THEN** the current round identifier, active winner, buzz history, submissions, and review status remain unchanged while new buzzes and submissions are rejected

#### Scenario: Teacher stops all active questions
- **WHEN** a teacher invokes the classroom stop-all operation
- **THEN** every affected question is locked without releasing its winner, clearing its interaction state, or starting a new round

### Requirement: Reset ends current interaction without deleting history
The system SHALL release current interaction locks and prepare affected questions for a later new round while preserving persistent historical records.

#### Scenario: Teacher resets one active or locked question
- **WHEN** a teacher resets a question in `BUZZ_OPEN`, `ANSWER_OPEN`, or `LOCKED`
- **THEN** the question becomes `READY`, its current Redis interaction keys are released, its current-round client state is reset, and its persistent buzz and submission history remains available

#### Scenario: Teacher resets all affected questions
- **WHEN** a teacher invokes reset-all
- **THEN** the same reset semantics are applied independently to each affected question and no new round is created until that question is opened again
