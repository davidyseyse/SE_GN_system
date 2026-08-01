## MODIFIED Requirements

### Requirement: Students can submit one answer per active buzz round

The system SHALL allow the active buzz winner to submit one answer in the current round without treating a submission from a previous round as a duplicate.

#### Scenario: Winner submits in a reopened round

- **WHEN** a student wins a new round after the teacher reset and reopened the same question
- **THEN** the student can enter and submit an answer even if that student submitted for the question in a previous round

#### Scenario: Duplicate submission in one round

- **WHEN** a student attempts a second submission in the same round
- **THEN** the system rejects it as a duplicate

### Requirement: Submitted answers cannot be released by students

The system MUST prevent a winner from cancelling the buzz lock after submitting an answer in the current round.

#### Scenario: Submit button completes the answer

- **WHEN** the current winner successfully submits an answer
- **THEN** the student client marks the current round as submitted and removes or hides the cancel-lock action

#### Scenario: Submitted winner calls the cancellation API

- **WHEN** a student requests cancellation after submitting an answer in the current round
- **THEN** the server rejects the cancellation and preserves the submitted answer and round state

### Requirement: Reset clears current student interaction state

The student client SHALL clear question interaction state belonging to an ended round when a teacher resets the question.

#### Scenario: Previously submitted student observes a reset

- **WHEN** the client receives a reset event for a question
- **THEN** it clears the ended round's local winner, answer draft, and submitted UI state so a newly opened round is interactive

