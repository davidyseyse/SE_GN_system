## Purpose

確保學生端能以伺服器提供的權威身分判定恢復目前回合的已提交狀態，避免刷新、重新連線或非同步回應使已完成作答重新取得取消鎖定操作。

## ADDED Requirements

### Requirement: Submission views identify the current student's own answer
The system SHALL provide an authenticated student with an unambiguous indication of whether each current-round submission belongs to that student, without requiring the client to compare identifiers from different identity namespaces.

#### Scenario: Student reads current-round answers
- **WHEN** an authenticated student requests the submitted answers for a question's current round
- **THEN** each returned submission indicates whether it belongs to the requesting student

#### Scenario: Another student's answer is returned
- **WHEN** the current-round answer list contains a submission from a different student
- **THEN** that submission is not identified as belonging to the requester

### Requirement: Submitted state survives authoritative refresh
The student client MUST preserve or restore the submitted state for the current question and round when authoritative answer data confirms that the student has submitted.

#### Scenario: Submission event refreshes the answer list
- **WHEN** a student successfully submits an answer and the resulting realtime event triggers an answer-list refresh
- **THEN** the question remains marked as submitted after the refresh completes
- **AND** the cancel-lock action remains hidden

#### Scenario: Student reloads or reconnects after submission
- **WHEN** a student reloads or reconnects during a round in which that student already submitted
- **THEN** authoritative answer data restores the submitted state for that question and round
- **AND** the client does not expose another submit or cancel-lock action

### Requirement: Stale refreshes cannot undo a successful submission
The student client MUST NOT allow an older asynchronous refresh result to clear a locally confirmed successful submission for the same question and round.

#### Scenario: Pre-submit refresh completes after submit success
- **WHEN** an answer-list request started before submission completes after the submission has succeeded
- **THEN** the older response does not remove the submitted state
- **AND** the cancel-lock action remains hidden

### Requirement: Submitted state remains scoped by question and round
The system MUST associate recovered and locally confirmed submission state with the specific question and buzz round.

#### Scenario: Another question remains interactive
- **WHEN** a student submits an answer for one of multiple active questions
- **THEN** only that question and round becomes submitted
- **AND** eligible interactions on other questions remain unchanged

#### Scenario: Teacher starts a new round
- **WHEN** the previous round ends and the teacher opens a new round for the same question
- **THEN** the previous round's submitted state does not mark the new round as submitted
