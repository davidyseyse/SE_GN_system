## MODIFIED Requirements

### Requirement: Buzz results are synchronized in real time
The system SHALL publish buzz window state changes, successful buzz outcomes, winner cancellations, answer submissions, stops, and resets to authorized student and teacher classroom clients in near real time with the question and round identifiers needed to apply them safely.

#### Scenario: Buzz opens for students and teachers
- **WHEN** a teacher opens a buzz-enabled question
- **THEN** the system publishes the question identifier, new round identifier, and open state so authorized clients can update that question

#### Scenario: Winner is announced
- **WHEN** a first successful buzzer is determined
- **THEN** the system publishes the question identifier, round identifier, winner identifier and display name so the teacher can show the winner without manual refresh

#### Scenario: Winner submits an answer
- **WHEN** the current-round winner successfully submits an answer
- **THEN** the system publishes the question identifier, round identifier, submitter identifier, submission status, and review status

#### Scenario: Current winner cancels
- **WHEN** the current-round winner cancels before submission
- **THEN** the system publishes a cancellation event that lets authorized clients remove that active winner and show the question as accepting buzzes again

#### Scenario: Teacher stops without resetting
- **WHEN** a teacher stops an active question or all active questions
- **THEN** the system publishes a state-change event to `LOCKED` without publishing a reset event or advancing the round

#### Scenario: Teacher resets a question
- **WHEN** a teacher resets one question or resets all affected questions
- **THEN** the system publishes a reset event for each affected question and round so clients clear ended-round interaction state

### Requirement: Clients synchronize cancellation by question and round
The system SHALL include the question and round identifiers in buzz winner, cancellation, submission, stop, and reset events, and clients MUST apply round-scoped events only to the matching current round.

#### Scenario: Client receives cancellation for the current round
- **WHEN** a client receives a cancellation event for the displayed current round
- **THEN** it removes the old winner and updates the question to accept eligible buzzes

#### Scenario: Client receives an event from an old round
- **WHEN** a client receives a winner, cancellation, submission, or reset event for a non-current round
- **THEN** it does not apply that event to the current-round UI

#### Scenario: Client reconnects without event history
- **WHEN** a client reconnects and prior realtime events are unavailable
- **THEN** it reconciles the current-round state from an authoritative classroom response
