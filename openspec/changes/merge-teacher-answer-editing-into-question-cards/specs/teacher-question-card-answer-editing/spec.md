## Purpose

Define how teachers inspect, edit, and manually enter current-round answers directly on question cards with explicit group attribution.

## ADDED Requirements

### Requirement: Current answers are managed on their question card

The teacher dashboard SHALL present current-round answers and their modification controls inside the corresponding question card and SHALL NOT present the separate `搶答檢討與學生答案修改` classroom panel.

#### Scenario: Student submits an answer

- **WHEN** a current-round answer is successfully submitted
- **THEN** the corresponding teacher question card displays its group and readable answer with a `修改答案` action

#### Scenario: Historical answers exist

- **WHEN** a question has submissions from earlier rounds
- **THEN** those historical submissions do not appear in the active question-card editor

### Requirement: Answer editing follows the question lifecycle

The system SHALL allow a teacher or assistant to edit an existing current-round answer after it is submitted and SHALL allow manual entry after the question is stopped.

#### Scenario: Submitted answer is edited before stop

- **WHEN** a current-round answer exists while the winner is answering or the question remains open
- **THEN** an authorized teacher or assistant can open the inline editor for that answer

#### Scenario: Question is stopped without a submission

- **WHEN** the teacher stops a question whose current round has no answer
- **THEN** the question card offers an enabled `修改答案` action that opens an empty manual-entry form

#### Scenario: Open question has no submission

- **WHEN** a question is open and its current round has no answer
- **THEN** the question card does not allow a teacher to create a manual answer

### Requirement: Group is editable per answer

The inline editor SHALL require a positive group number and SHALL update only the current answer's group snapshot.

#### Scenario: Teacher changes an answer group

- **WHEN** a teacher saves a different valid group number
- **THEN** the current answer is attributed to the new group without changing the student's login identity, default group, or answers on other questions

#### Scenario: Group is invalid

- **WHEN** the group is empty, non-numeric, zero, or negative
- **THEN** the system rejects the save and preserves the editor values for correction

### Requirement: Original buzz identity and challenge group are distinct

The teacher question card SHALL preserve the original buzz identity and SHALL always display the answer group with the separate label `挑戰：第N組`, including when it equals the original group.

#### Scenario: Challenge group differs from original group

- **WHEN** the original buzz identity is `1 / 第1組` and the answer group is changed to `6`
- **THEN** the card displays `1 / 第1組　挑戰：第6組` and does not display `1 / 第1組／第6組`

#### Scenario: Challenge group equals original group

- **WHEN** the original buzz identity is `1 / 第1組` and the answer group remains `1`
- **THEN** the card displays `1 / 第1組　挑戰：第1組`

#### Scenario: Teacher manually enters an answer without a winner

- **WHEN** a stopped question has no original student winner and the teacher enters group `6`
- **THEN** the answer attribution displays `挑戰：第6組`

### Requirement: Package answers use labeled item fields

The teacher question card SHALL display and edit package answers as one prompt-labeled value per current question item and SHALL NOT expose stored item UUID JSON.

#### Scenario: Package answer is displayed

- **WHEN** a stored answer is an item-keyed package-answer object
- **THEN** the card maps each value to its question-item prompt in ordinal order

#### Scenario: Package answer is saved

- **WHEN** a teacher edits one or more labeled item values and saves
- **THEN** the system persists an item-ID-keyed answer object matching those fields

### Requirement: Saving reconciles retained integrations

The system SHALL publish an answer-change notification and reconcile the affected current-round answer after a teacher saves an answer or group.

The configured Google Sheets cell SHALL use `答案_G組別` formatting.

#### Scenario: Teacher saves an edit

- **WHEN** an authorized answer or group update succeeds
- **THEN** connected teacher and student clients display the authoritative updated value and the configured Google Sheet is synchronized

#### Scenario: Group answer is synchronized

- **WHEN** answer `ans` attributed to group `1` is synchronized
- **THEN** the Google Sheets value is `ans_G1`

#### Scenario: Teacher changes answer and challenge group

- **WHEN** a teacher saves answer `ans` with challenge group `6`
- **THEN** the bound Google Sheets cell is updated to `ans_G6`

#### Scenario: Teacher changes a package answer

- **WHEN** a teacher saves changed values for a package answer
- **THEN** every bound item cell is updated using its changed answer and the challenge-group suffix

#### Scenario: Google Sheets update fails after answer persistence

- **WHEN** the answer is persisted but Sheets configuration, authorization, network, or API synchronization fails
- **THEN** the mutation reports that the answer was saved but Sheets synchronization failed, records a retryable synchronization state and error reason, and allows synchronization to be retried without another answer edit
