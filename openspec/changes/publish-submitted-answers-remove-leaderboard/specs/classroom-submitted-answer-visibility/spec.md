## Purpose

Define how successfully submitted answers become automatically visible to authenticated members of the same classroom and remain recoverable without exposing internal round or timestamp metadata.

## ADDED Requirements

### Requirement: Successful answers are immediately visible to the classroom

The system SHALL make a successfully persisted answer visible on the corresponding question card for the teacher, assistants, the submitting student, and all other authenticated members of that classroom.

#### Scenario: Student successfully submits an answer

- **WHEN** a student submits an answer and the server persists it successfully
- **THEN** connected teacher and student clients automatically display the submitting student's display identity and answer on the corresponding question without requiring a user to request or refresh the answers manually

#### Scenario: Submission fails

- **WHEN** the server does not persist a submitted answer
- **THEN** no client presents that attempt as a successfully submitted classroom answer

### Requirement: Answer visibility requires classroom membership

The system MUST authorize answer reads by verified membership in the requested classroom and MUST verify that the requested question belongs to that classroom.

#### Scenario: Classroom member reads current answers

- **WHEN** an authenticated teacher, assistant, or student who belongs to the classroom requests answers for a question in that classroom
- **THEN** the system returns the current answers permitted by this capability

#### Scenario: Authenticated non-member attempts to read answers

- **WHEN** an authenticated user who does not belong to the classroom requests its answers
- **THEN** the system rejects the request without disclosing answer data

#### Scenario: Question belongs to another classroom

- **WHEN** a member supplies a question identifier that is not part of the requested classroom
- **THEN** the system rejects the request without disclosing answer data

### Requirement: Current answers recover automatically

The system SHALL restore current submitted answers from authoritative persisted data when a classroom member enters or refreshes the classroom, reconnects realtime transport, or completes realtime resynchronization.

#### Scenario: Student refreshes after submitting

- **WHEN** a student refreshes or returns to the classroom after a successful submission
- **THEN** the student's answer and all other current classroom answers reappear automatically

#### Scenario: Client misses a realtime event

- **WHEN** a client reconnects after missing one or more submission-change events
- **THEN** the client reconciles from persisted current answers and displays the same result as continuously connected clients

### Requirement: Active answer presentation hides internal metadata

The current classroom question card SHALL display the student's existing display identity, submitted answer, and the visible status `✓ 已作答`, and SHALL NOT display submission time or round number.

#### Scenario: Current answer is rendered

- **WHEN** a current submitted answer is displayed on a teacher or student question card
- **THEN** the card shows the submitting student's display identity, answer text, and `✓ 已作答` without showing a submission timestamp, `PENDING_REVIEW`, or a round label

### Requirement: Reset separates current and historical answers

The system SHALL use the internal question round to determine current answers while keeping the round identifier hidden from users.

#### Scenario: Teacher resets and reopens a question

- **WHEN** the teacher resets a question and later opens a new round
- **THEN** ended-round answers disappear from the active question card, the new round begins with no displayed current answers, and historical submissions remain persisted for administrative reporting

### Requirement: Answer changes propagate automatically

The system SHALL notify connected classroom clients after an authorized answer create, update, delete, or review operation and SHALL reconcile the affected question from authoritative data.

#### Scenario: Teacher edits or deletes an answer

- **WHEN** an authorized teacher or assistant changes or deletes a submitted answer
- **THEN** all connected classroom clients automatically update or remove the corresponding visible answer

