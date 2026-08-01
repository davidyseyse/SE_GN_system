## Purpose

Defines package-level lifecycle, buzz competition, winner authorization, multi-subquestion answer submission, and synchronization of each answer to its bound Google Sheets cell.

## ADDED Requirements

### Requirement: Question package is the lifecycle unit
The system SHALL apply readiness, buzz opening, locking, review, reset, and closure to an entire question package rather than independently to its subquestions.

#### Scenario: Teacher opens a package
- **WHEN** a teacher opens an eligible question package for buzzing
- **THEN** all authorized student clients see that package as open with one buzz action for the package

#### Scenario: Teacher resets a package
- **WHEN** a teacher resets an active or locked package
- **THEN** the system ends the current package round, releases its current interaction lock, preserves its history, and returns the entire package to the ready state

#### Scenario: Batch action targets packages
- **WHEN** a teacher invokes an open-all, stop-all, or reset-all action
- **THEN** the system evaluates and reports the result for each eligible package rather than each subquestion

### Requirement: One student wins each package buzz round
The system MUST determine at most one successful first buzzer for each question-package round using server-side concurrency control.

#### Scenario: Concurrent students buzz for one package
- **WHEN** multiple students buzz during the same open package round
- **THEN** the system records exactly one winner for that package round and rejects later winning claims

#### Scenario: Student attempts to buzz a subquestion
- **WHEN** a client attempts to address a subquestion as an independent buzz target
- **THEN** the system rejects the request because only the containing package can be buzzed

#### Scenario: Packages are independent
- **WHEN** different packages are simultaneously open
- **THEN** each package maintains its own round, lock, winner, and buzz history

### Requirement: Package winner answers all subquestions
The system SHALL grant the package buzz winner the right to answer every subquestion in that package through one package submission flow.

#### Scenario: Winner submits a complete package answer
- **WHEN** the current winner supplies one answer for every subquestion while the package accepts answers
- **THEN** the system stores the ordered answers as one submission for the current package round and acknowledges the complete submission

#### Scenario: Non-winner attempts submission
- **WHEN** a student other than the current package winner submits package answers during a winner-restricted buzz round
- **THEN** the system rejects the submission without storing any answers

#### Scenario: Required subquestion answer is missing
- **WHEN** the winner submits a package answer that omits a subquestion
- **THEN** the system rejects the whole submission and identifies the missing subquestion without storing a partial submission

#### Scenario: Package is no longer accepting answers
- **WHEN** the winner submits after the package has been locked or reset
- **THEN** the system rejects the submission without altering the completed or current package round

### Requirement: Subquestion answers synchronize to bound cells
The system SHALL write each stored subquestion answer to that subquestion's target cell in the package's selected Google Sheets configuration.

#### Scenario: Package contains multiple bindings
- **WHEN** a valid package submission contains answers for multiple subquestions
- **THEN** the system maps every answer to its corresponding binding and writes each value to the configured cell

#### Scenario: Binding identity is preserved
- **WHEN** subquestions are displayed, renumbered before creation, or later retrieved
- **THEN** the system uses persistent subquestion identity rather than a client array index to associate submitted answers with cells

### Requirement: Package state is synchronized in real time
The system SHALL publish package state changes, buzz outcomes, resets, and submission status to authorized classroom clients in near real time.

#### Scenario: Package winner is determined
- **WHEN** the server accepts the first buzz for a package round
- **THEN** authorized clients receive an event identifying the package, round, and winner and update only that package

#### Scenario: Package is reset
- **WHEN** a teacher resets a package
- **THEN** authorized clients receive a package reset event and clear winner and draft-submission state for the ended round

