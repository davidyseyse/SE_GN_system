## Purpose

Extends teacher package management with generated-package provenance and role-specific visibility.

## MODIFIED Requirements

### Requirement: Package management distinguishes authoring provenance from answer binding
The system SHALL retain answer spreadsheet configuration and per-item target cells for every generated package and SHALL additionally retain its marker-detection source without changing manual package behavior.

#### Scenario: Teacher views a generated package
- **WHEN** an authorized teacher views a generated package
- **THEN** the package management response may show the source spreadsheet/worksheet/cell and answer spreadsheet/worksheet/cell for each item

#### Scenario: Teacher views a manual package
- **WHEN** an authorized teacher views a manually created package
- **THEN** the package remains manageable without requiring marker-source provenance

#### Scenario: Student views either package type
- **WHEN** a student views a generated or manual package
- **THEN** the response omits all spreadsheet configuration and cell-binding details

### Requirement: Deleting a Sheet configuration removes dependent packages atomically
The system SHALL require teacher confirmation before deleting a Sheet configuration and SHALL delete every package that uses the configuration as an answer binding or generated source binding in the same transaction as the configuration. The response SHALL report how many dependent packages were deleted.

#### Scenario: Configuration is used by packages
- **WHEN** a teacher confirms deletion of a Sheet configuration referenced by one or more packages
- **THEN** the dependent packages and their cascaded classroom records are deleted before the configuration
- **AND** the teacher sees how many packages were removed

#### Scenario: Configuration deletion fails
- **WHEN** any dependent-package or configuration delete fails
- **THEN** the transaction rolls back without partially deleting packages or the configuration

#### Scenario: Teacher cancels deletion
- **WHEN** the teacher declines the destructive confirmation
- **THEN** the client sends no deletion request and preserves the configuration and packages

### Requirement: Sheet statistics award one point per group per cell
The teacher Sheet-statistics operation SHALL split a cell on `/` or `／`, recognize each segment ending in a positive `_G<number>` suffix, and award each distinct group at most one point for that cell. Points from different cells SHALL accumulate.

#### Scenario: One cell contains answers from different groups
- **WHEN** a cell contains `fdsfs_G1/ asda_G2`
- **THEN** group 1 receives one point and group 2 receives one point

#### Scenario: One group appears repeatedly in one cell
- **WHEN** a cell contains `first_G1/ retry_G1`
- **THEN** group 1 receives only one point for that cell

#### Scenario: One group appears in different cells
- **WHEN** two separate cells each contain a segment ending in `_G1`
- **THEN** group 1 receives two points

#### Scenario: Teacher views the Sheet statistics result
- **WHEN** Sheet statistics contain one or more recognized groups
- **THEN** the teacher interface lists each distinct group once as `第 N 組`
- **AND** it does not display per-group points, successful-answer counts, or a total score

### Requirement: Buzz answers are finalized only during teacher review
The system SHALL persist each answer submitted for an active buzz attempt as pending without selecting a winner, incrementing a group success count, awarding a buzz bonus, or writing Google Sheets. The system SHALL perform those effects only after an authorized teacher invokes `完成搶答並檢討`.

#### Scenario: Student submits before review
- **WHEN** a student completes the answers and submits an active buzz attempt
- **THEN** the system stores the answer as pending and tells the student that submission succeeded
- **AND** the system does not reveal whether the attempt will win or write the answer to Google Sheets

#### Scenario: Pending submission is retried
- **WHEN** the same student retries an answer that was already stored for the same question and round because the first response was lost or delayed
- **THEN** the system treats the request as a successful pending submission instead of reporting buzz failure
- **AND** it preserves exactly one submission for that student and round

#### Scenario: Submission is not accepted before review
- **WHEN** an answer or buzz request is rejected before teacher review has produced a final result
- **THEN** the student is told that the answer has not yet been submitted successfully and may retry
- **AND** the interface does not describe the attempt as a buzz failure

#### Scenario: Teacher completes buzz review
- **WHEN** the teacher stops the question and invokes `完成搶答並檢討`
- **THEN** the system evaluates submitted attempts in receipt order
- **AND** the fastest submitted attempt whose group has not reached the current Taipei-day global success limit becomes the only winner
- **AND** only the winner's answer is synchronized to the configured Google Sheet

#### Scenario: Teacher completes all open buzz questions
- **WHEN** the teacher invokes the classroom-level `完成搶答並檢討` action
- **THEN** the system stops every active buzz question and finalizes each question in deterministic order
- **AND** each question's winning answer is synchronized to its configured Excel or Google Sheet destination
- **AND** one question's synchronization failure does not prevent the remaining questions from being finalized

#### Scenario: Earlier group has reached its daily limit
- **WHEN** an earlier submitted attempt belongs to a group that already reached the global daily limit and a later eligible group submitted an answer
- **THEN** the earlier attempt fails and the later eligible attempt becomes the winner

#### Scenario: Teacher assigns a challenge target
- **WHEN** a teacher updates a submitted answer to challenge a different group
- **THEN** the challenged group's current Taipei-day global success count is cleared in the same transaction as the answer update
- **AND** that group is eligible to win a later buzz question

#### Scenario: Challenged answer is finalized
- **WHEN** a pending challenged answer becomes the finalized winner
- **THEN** the challenge target remains the answer's displayed and Sheet-attribution group
- **AND** the successful-buzz slot is consumed by the submitting student's original group rather than the challenged group

#### Scenario: Review result reaches students
- **WHEN** teacher review finishes
- **THEN** each submitting student sees either `搶答成功` or `搶答失敗` for their own submission
- **AND** a failed student is not told whether failure was caused by order or the group success limit

#### Scenario: Teacher views submitted answers
- **WHEN** more than two groups submit answers for the same question round
- **THEN** the teacher question card displays only the earliest submission from each of the two fastest distinct groups
- **AND** later submissions and duplicate submissions from an already displayed group remain stored but are not shown in the card

#### Scenario: Question is reopened for a new round
- **WHEN** a previously reviewed question starts a new round
- **THEN** the student does not see the prior round's success or failure notice on the new answer form

### Requirement: Reset all questions clears response records
The classroom-level `RESET_ALL` operation SHALL reset each eligible buzz question and delete all submissions and buzz records belonging to that question in the same database transaction. A `READY` buzz question SHALL still be processed when it contains residual response records.

#### Scenario: Teacher confirms reset all
- **WHEN** the teacher confirms `重置全部題目`
- **THEN** active or locked buzz questions return to `READY`
- **AND** all submissions and buzz records for every eligible buzz question are deleted
- **AND** the teacher interface clears its cached answer cards

#### Scenario: Record deletion fails
- **WHEN** deleting a question's submissions or buzz records fails
- **THEN** that question's state change and record deletions roll back together
- **AND** the batch reports that question as failed without partially resetting it

#### Scenario: Independent counters and Sheet values remain
- **WHEN** `RESET_ALL` completes
- **THEN** daily global group success counters are unchanged
- **AND** values already synchronized to Google Sheets are unchanged

