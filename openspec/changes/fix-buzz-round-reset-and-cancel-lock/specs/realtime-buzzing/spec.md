## ADDED Requirements

### Requirement: An unsubmitted winner can release the buzz lock

The system SHALL allow the active winner of the current buzz round to cancel their lock before submitting an answer.

#### Scenario: Winner cancels before submitting

- **WHEN** the current winner cancels while the question is `BUZZ_OPEN` and no answer has been submitted by that winner in the current round
- **THEN** the system marks that winning claim inactive, releases the lock, keeps the question open, and publishes a cancellation event

#### Scenario: Another student buzzes after cancellation

- **WHEN** an active winner has cancelled before submission
- **THEN** other students can immediately buzz and the next accepted winner can enter and submit an answer without interference from the cancelled winner

#### Scenario: Non-winner attempts to cancel

- **WHEN** a student who is not the current active winner requests cancellation
- **THEN** the system rejects the request without changing the active lock

### Requirement: Clients synchronize cancellation by question and round

The system SHALL include the question and round identifiers in buzz winner, cancellation, and reset events.

#### Scenario: Client receives cancellation for the current round

- **WHEN** a student client receives a cancellation event for the displayed current round
- **THEN** it removes the old winner, clears that winner's unsubmitted local draft, and enables buzzing for eligible students

#### Scenario: Client receives an event from an old round

- **WHEN** a client receives a winner or cancellation event for a non-current round
- **THEN** it does not apply that event to the current round UI

