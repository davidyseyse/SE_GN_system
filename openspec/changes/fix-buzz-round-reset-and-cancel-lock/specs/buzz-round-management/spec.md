## ADDED Requirements

### Requirement: Buzz attempts are isolated by round

The system SHALL associate every buzz attempt, active winner, and answer submission for a buzz question with a persistent buzz round.

#### Scenario: Teacher reopens a reset question

- **WHEN** a teacher opens a buzz question after resetting its previous round
- **THEN** the system creates a new round whose winner and submissions are independent of all previous rounds

#### Scenario: Historical rounds remain auditable

- **WHEN** a new round is created for a question
- **THEN** the system preserves previous-round buzz attempts, winners, cancellations, and submissions as historical records

### Requirement: A round has at most one active winner

The system MUST allow at most one active successful buzz winner for a question round at any instant.

#### Scenario: Concurrent buzz attempts

- **WHEN** multiple students buzz concurrently in an open round
- **THEN** the system accepts exactly one active winner and records later attempts as unsuccessful

#### Scenario: Cancelled winner is replaced

- **WHEN** the active winner cancels before submitting an answer
- **THEN** the winner becomes inactive and a later buzz may become the new active winner in the same round

### Requirement: Current-round decisions ignore historical records

The system MUST use only the current round when determining the active winner or whether a student has already submitted.

#### Scenario: Previous winner participates in a new round

- **WHEN** a student who won or submitted in a previous round buzzes in a newly opened round
- **THEN** the previous-round records do not prevent the new buzz or a new answer submission

