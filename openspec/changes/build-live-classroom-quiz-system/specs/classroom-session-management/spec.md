## ADDED Requirements

### Requirement: Teacher can manage classroom sessions
The system SHALL allow a teacher to create, update, open, and close a classroom session with a unique classroom code and classroom-level participation settings.

#### Scenario: Teacher creates a classroom
- **WHEN** a teacher submits a valid classroom name and participation settings
- **THEN** the system creates the classroom, generates a unique classroom code, and returns the classroom control view data

#### Scenario: Teacher updates classroom settings
- **WHEN** a teacher updates participation settings for an existing classroom
- **THEN** the system persists the new settings and applies them to future student joins

### Requirement: Student can join classroom sessions
The system SHALL allow a student to join a classroom by classroom code only when the classroom is active and the join request satisfies classroom participation rules.

#### Scenario: Student joins with valid code
- **WHEN** a student submits a valid classroom code for an active classroom
- **THEN** the system enrolls the student in the classroom and returns the current classroom snapshot

#### Scenario: Student is rejected by classroom rules
- **WHEN** a student submits a join request that violates classroom participation rules
- **THEN** the system rejects the request and returns an authorization or validation error

### Requirement: Classroom presence is synchronized
The system SHALL maintain current classroom presence and publish presence changes to authorized classroom participants in near real time.

#### Scenario: Presence update is broadcast
- **WHEN** a student joins, disconnects, or reconnects to a classroom session
- **THEN** the system updates the presence state and publishes a presence update event to authorized classroom clients

#### Scenario: Reconnected client receives current state
- **WHEN** a previously disconnected classroom client reconnects successfully
- **THEN** the system returns the latest classroom membership and session state before resuming live updates
