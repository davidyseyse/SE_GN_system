## ADDED Requirements

### Requirement: The system determines a single first buzzer
The system MUST determine exactly one first successful buzzer for each buzz-enabled question using server-side concurrency control and server receipt time.

#### Scenario: Single winner is recorded
- **WHEN** multiple students submit a buzz request during an open buzz window
- **THEN** the system records exactly one student as the first successful buzzer and rejects all later winning claims for that question

#### Scenario: Closed buzz window rejects requests
- **WHEN** a student submits a buzz request before the buzz window opens or after it closes
- **THEN** the system rejects the request and does not create a successful buzz record

### Requirement: Buzz ordering is auditable
The system SHALL store the ordered buzz attempts with timestamps and outcome status for later teacher review and dispute resolution.

#### Scenario: Buzz history is preserved
- **WHEN** buzz requests are processed for a question
- **THEN** the system stores each attempt with its order, receipt timestamp, requester, and success or failure outcome

#### Scenario: Teacher reviews buzz sequence
- **WHEN** a teacher opens the buzz history for a question
- **THEN** the system returns the recorded sequence in order of server acceptance

### Requirement: Buzz results are synchronized in real time
The system SHALL publish buzz window state changes and successful buzz outcomes to authorized classroom clients in near real time.

#### Scenario: Buzz opens for students
- **WHEN** a teacher opens a buzz-enabled question
- **THEN** the system publishes a buzz-open event so student clients can enable the buzz action

#### Scenario: Winner is announced
- **WHEN** a first successful buzzer is determined
- **THEN** the system publishes the winner and lock state to authorized classroom clients without requiring manual refresh
