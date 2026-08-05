## Purpose

讓教師可靠掌握每一題目前搶答回合的開放、勝者、作答及待檢討狀態，並在即時事件、重新整理或重新連線後看到一致結果。

## ADDED Requirements

### Requirement: Teachers can see the current buzz status of every question
The system SHALL show an authorized teacher or assistant each question's lifecycle state together with its current-round buzz and submission status.

#### Scenario: Open question has no winner
- **WHEN** a buzz question is open and the current round has no active winner
- **THEN** the teacher sees that the question is accepting buzzes and is waiting for a winner

#### Scenario: Student wins the buzz
- **WHEN** a student becomes the active winner of the current round
- **THEN** the teacher sees that the question has been won and sees the winner's display name

#### Scenario: Winner submits an answer
- **WHEN** the active winner submits an answer in the current round
- **THEN** the teacher sees that the answer has been submitted and is pending review

#### Scenario: Winner cancels before submission
- **WHEN** the active winner cancels before submitting
- **THEN** the teacher sees the question return to the waiting-for-winner state without showing the cancelled student as active

### Requirement: Teacher buzz status is synchronized and recoverable
The system SHALL update teacher buzz status in near real time and SHALL reconcile it from authoritative current-round data after page refresh or realtime reconnection.

#### Scenario: Teacher receives a realtime update
- **WHEN** a current-round winner, cancellation, submission, stop, or reset event occurs
- **THEN** the affected question status updates without a manual page refresh

#### Scenario: Teacher reloads after a winner exists
- **WHEN** a teacher loads or reconnects to the classroom after a student has already won or submitted
- **THEN** the teacher sees the current winner and submission status even when earlier realtime events are unavailable

#### Scenario: Old-round event arrives
- **WHEN** the teacher client receives an event whose round identifier is not the question's current round
- **THEN** the client does not apply that event to the current-round status

### Requirement: Buzz states are visually distinguishable
The teacher interface SHALL distinguish not open, waiting for a buzz, winner answering, submitted pending review, and stopped states using clear text plus visual indicators that do not rely on color alone.

#### Scenario: Teacher scans questions in mixed states
- **WHEN** a classroom contains questions in different lifecycle or current-round states
- **THEN** each question presents a distinct status label and accessible visual treatment
