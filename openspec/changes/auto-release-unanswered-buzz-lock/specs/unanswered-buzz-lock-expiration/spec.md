## ADDED Requirements

### Requirement: A successful buzz starts a stable answer deadline
The system SHALL snapshot an answer deadline for each successful buzz winner by adding the question or package's positive `answerTimeLimitSeconds` value to the server-recorded winning time. Changing the configured limit after the buzz MUST NOT change that winner's deadline, and a missing or non-positive legacy value SHALL preserve the existing no-automatic-expiration behavior.

#### Scenario: Timed question gets a winner
- **WHEN** a student becomes the active winner for a question configured with a positive N-second answer limit
- **THEN** the system stores a deadline exactly N seconds after the server-recorded winning time for that winner and round

#### Scenario: Time limit changes after a win
- **WHEN** a teacher edits the question's answer time limit after a winner deadline has been stored
- **THEN** the current winner keeps the original deadline and a later winner uses the newly configured value

#### Scenario: Legacy question has no positive limit
- **WHEN** a student wins a legacy question whose answer time limit is absent or non-positive
- **THEN** the system does not automatically expire that winner

### Requirement: Unanswered winners expire without ending the round
The system MUST automatically make an active winner non-active when its stored deadline has passed and no submission was successfully persisted for that winner, question, and round. Expiration SHALL retain history, keep the question in `BUZZ_OPEN`, preserve the current round, release the corresponding Redis winner lock, and allow another student to win.

#### Scenario: Winner does not submit before N seconds
- **WHEN** the active winner's deadline passes with no current-round submission
- **THEN** the system records that winner as `TIMED_OUT`, releases the active lock, and leaves the same round open for another buzz

#### Scenario: Another student buzzes after expiration
- **WHEN** an unanswered winner has expired and another student submits a valid buzz request
- **THEN** the other student can become the active winner and receives a new full answer deadline

#### Scenario: Question is no longer open
- **WHEN** a deadline passes after the teacher stopped or reset the question
- **THEN** timeout processing does not reopen the question or otherwise override the teacher's lifecycle action

### Requirement: Submission and expiration have one atomic outcome
The system MUST serialize current-winner submission persistence and timeout expiration against the same active winner record so that exactly one outcome succeeds. A submission accepted before the deadline SHALL prevent expiration, while a submission evaluated at or after the deadline SHALL be rejected and SHALL NOT create a submission.

#### Scenario: Submission wins the deadline race
- **WHEN** the active winner's valid submission is accepted before timeout processing expires the winner
- **THEN** the submission is persisted and subsequent timeout processing leaves the winner and submission unchanged

#### Scenario: Timeout wins the deadline race
- **WHEN** timeout processing expires the active winner before that student's submission is accepted
- **THEN** the late submission is rejected, no answer row is created, and another student may buzz

#### Scenario: Concurrent submit and timeout
- **WHEN** submission and expiration execute concurrently at the deadline boundary
- **THEN** database coordination produces exactly one committed result without an active timed-out winner, duplicate submission, or stuck Redis lock

### Requirement: Timeout processing is recoverable and idempotent
The system SHALL detect overdue unanswered winners during normal background processing and immediately when the API starts. Repeated sweeps and concurrent API instances MUST NOT expire the same winner or publish its release more than once.

#### Scenario: API restarts during an answer window
- **WHEN** the API starts and finds an active unanswered winner whose stored deadline has already passed
- **THEN** it expires and releases that winner before accepting normal classroom traffic

#### Scenario: Timeout sweep repeats
- **WHEN** timeout processing evaluates an already expired, submitted, manually cancelled, reset, or superseded winner
- **THEN** it performs no additional state transition and publishes no duplicate release event

### Requirement: Timeout release reconciles connected clients
After committing an automatic expiration, the system SHALL publish a current-round classroom realtime release event identified as timeout-driven. Student and teacher clients MUST clear only the matching winner, preserve the question's `BUZZ_OPEN` state, and show that competition is available again.

#### Scenario: Connected clients receive a timeout release
- **WHEN** an unanswered winner is automatically expired
- **THEN** the system publishes `BUZZ_CANCELLED` with the package/question ID, round ID, expired user identity, and `reason` equal to `TIMEOUT`

#### Scenario: Student receives current-round timeout
- **WHEN** a student client receives the timeout release for its displayed current round
- **THEN** it clears the expired winner and draft, indicates that answer time expired, and re-enables the buzz action

#### Scenario: Client receives stale timeout
- **WHEN** a client receives a timeout release for an older round
- **THEN** it ignores that release without changing the current winner or draft

### Requirement: The active student winner sees the remaining answer time
The student client SHALL display a countdown only to the current active buzz winner, derived from the absolute server-stored answer deadline. Refresh, reconnect, and current-question reconciliation MUST restore the remaining time without starting a new full countdown. The client timer MUST NOT independently authorize submission or release the winner lock.

#### Scenario: Student wins a timed buzz
- **WHEN** the current student becomes the active winner with a stored answer deadline
- **THEN** that student's client shows the remaining whole seconds and other students do not see a winner answer countdown

#### Scenario: Winner refreshes during the answer window
- **WHEN** the active winner reloads or reconnects before the stored deadline
- **THEN** the countdown resumes from the remaining time represented by the original server deadline

#### Scenario: Countdown reaches zero
- **WHEN** the displayed remaining time reaches zero before timeout reconciliation arrives
- **THEN** the client shows that server release is pending and does not locally create a new winner or accept an answer

#### Scenario: Server expires the unanswered winner
- **WHEN** the server commits timeout expiration and publishes the matching current-round release
- **THEN** every student client clears the expired winner, returns the question to `BUZZ_OPEN`, and allows all eligible students to buzz again in the same round

#### Scenario: Winner state ends before timeout
- **WHEN** an answer is submitted, the winner is manually cancelled, the question is reset, or a new round starts
- **THEN** the student countdown is cleared or replaced by the authoritative current winner state

### Requirement: Teachers can configure N as a positive number of seconds
The teacher authoring interface and question/package APIs SHALL accept a positive whole-number answer time limit, default new package authoring to 30 seconds, and reject an explicitly supplied zero, negative, fractional, or malformed value.

#### Scenario: Teacher creates a package with N seconds
- **WHEN** a teacher enters a positive whole-number answer time limit and creates a question package
- **THEN** the stored package and subsequent buzz winner use that configured N-second limit

#### Scenario: Teacher supplies an invalid limit
- **WHEN** a create or update request explicitly supplies a non-positive, fractional, or malformed answer time limit
- **THEN** the system rejects the request without creating or changing the question or package
