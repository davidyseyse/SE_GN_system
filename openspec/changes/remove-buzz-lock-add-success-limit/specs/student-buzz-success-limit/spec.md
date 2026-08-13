## ADDED Requirements

### Requirement: System enforces one completed buzz win per group each day
The system SHALL enforce a fixed completed buzz-win limit of one for each positive group number across all classrooms during each Asia/Taipei calendar day. Teachers SHALL NOT configure this limit when creating or resuming a classroom.

#### Scenario: Teacher creates a classroom
- **WHEN** a teacher creates a classroom
- **THEN** the system applies the fixed global group limit of one
- **AND** the teacher interface does not display a success-limit setting

#### Scenario: Same group enters another classroom
- **WHEN** a group number has already completed one winning answer in any classroom on the same Taipei calendar day
- **THEN** students with that group number cannot record another winning answer in a different classroom

#### Scenario: Taipei calendar day changes
- **WHEN** Taipei local time passes midnight after a group reached its prior-day limit
- **THEN** that group's current-day completed-success count is zero
- **AND** the group can complete one winning answer during the new day

### Requirement: Group success is counted only after answer persistence
The system SHALL increment the submitting student's dated global group success count only in the same database transaction that commits their accepted fastest answer. A buzz click, a skipped submission, a failed transaction, and a Sheets retry SHALL NOT increment the count.

#### Scenario: Winner answer commits
- **WHEN** a fastest eligible student whose global group count is below one submits a valid answer
- **THEN** the submission and incremented global group success count commit atomically
- **AND** the response includes the updated count and fixed limit

#### Scenario: Submission persistence fails
- **WHEN** winner submission persistence fails
- **THEN** the global group success counter increment is rolled back

#### Scenario: Google Sheets fails after commit
- **WHEN** the answer and global group success count commit but Google Sheets synchronization fails
- **THEN** the success remains counted exactly once
- **AND** retrying Sheets does not increment the success count again

### Requirement: A group reaching the daily global limit skips same-day writes
The system SHALL reject a buzz or answer that would exceed the fixed daily global limit of one. The rejected operation SHALL expose a `SUCCESS_LIMIT_REACHED` outcome and SHALL NOT persist a new buzz attempt, submission, score record, or Google Sheets update.

#### Scenario: Group already won in another classroom
- **WHEN** a student buzzes after the same group number has completed a winning answer in any classroom
- **THEN** the response reports `SUCCESS_LIMIT_REACHED`
- **AND** no buzz attempt or receipt sequence is persisted for that request

#### Scenario: Global group limit is reached concurrently
- **WHEN** students with the same group number submit concurrently in different classrooms
- **THEN** exactly one pending submission may consume the global slot
- **AND** every other answer, score, success increment, and Sheets update is skipped atomically

### Requirement: Current-day global group success usage is visible
The system SHALL expose the student's group number, current-day global completed-success count, and fixed limit to the student buzz response and teacher member view.

#### Scenario: Student buzz response reports group usage
- **WHEN** an eligible student registers a buzz attempt
- **THEN** the response includes the current group number, `groupSuccessfulBuzzCount`, and `successfulBuzzLimit` equal to one

#### Scenario: Teacher views classroom members
- **WHEN** a teacher or assistant loads the classroom member list
- **THEN** each student entry includes its group number and that group's global completed buzz success count
- **AND** the classroom response presents the fixed limit of one

### Requirement: Student feedback does not reveal the group-limit reason
The student interface SHALL display the same neutral unsuccessful-buzz message when a buzz or answer is skipped because of `SUCCESS_LIMIT_REACHED`, `NOT_FASTEST`, or an already committed winner. It SHALL NOT mention the group's successful-buzz count, limit, or that the limit was reached. The API SHALL retain its distinct outcome status for rule enforcement and teacher-side observability.

#### Scenario: Student group has reached the limit
- **WHEN** the student's buzz or answer is rejected with `SUCCESS_LIMIT_REACHED`
- **THEN** the student sees a neutral message that the buzz was unsuccessful and the answer was not written
- **AND** the interface does not mention a count, limit, or group-limit reason

#### Scenario: Student is not the accepted winner
- **WHEN** the student's answer is skipped for another non-accepted outcome
- **THEN** the student sees the same neutral unsuccessful-buzz message

### Requirement: Classroom creation controls remain usable
The teacher classroom-name input and existing-classroom selector SHALL remain aligned without overflow. The create, resume, and delete buttons SHALL appear below the classroom-code status. The create and resume buttons SHALL use the same contrasting style. No success-limit input SHALL be displayed.

#### Scenario: Teacher views the creation form on desktop
- **WHEN** the dashboard has desktop-width space
- **THEN** the classroom name and resume-code controls remain within the available width
- **AND** the create and resume buttons appear below the classroom-code status with visibly contrasting colors

### Requirement: Teacher selects and deletes owned classrooms
The teacher dashboard SHALL list only classrooms owned by the authenticated teacher or assistant in a selector instead of accepting a free-text resume code. The selector SHALL initially be blank and SHALL be automatically populated only after successful classroom creation. The API SHALL reject deletion by a non-owner.

#### Scenario: Teacher creates a classroom
- **WHEN** classroom creation succeeds
- **THEN** the new classroom appears in the selector and becomes selected
- **AND** the classroom becomes the active dashboard classroom

#### Scenario: Teacher returns to an existing classroom
- **WHEN** the teacher explicitly selects an owned classroom and activates the resume action
- **THEN** the dashboard joins and opens that classroom

#### Scenario: Teacher deletes a selected classroom
- **WHEN** the teacher confirms deletion of a selected owned classroom
- **THEN** the classroom and its cascaded classroom data are deleted
- **AND** the selector no longer contains that classroom
- **AND** active dashboard state is cleared when it referred to the deleted classroom

### Requirement: Teacher computes successful-group scores from a linked sheet
The system SHALL let a teacher or assistant select an existing Sheet configuration and request an on-demand score statistic. The system SHALL read populated display values from every worksheet in the linked spreadsheet, count each positive numeric `_G<number>` suffix as one successful answer for that group, and return results ordered by group number. The student interface SHALL NOT expose this operation.

#### Scenario: Linked sheet contains successful answers
- **WHEN** the selected spreadsheet contains values ending in `_G2`, `_G2`, and `_G5`
- **THEN** the teacher sees group 2 with two successes and group 5 with one success
- **AND** the result reports two successful groups and three total successful-answer markers

#### Scenario: Linked sheet has no successful group marker
- **WHEN** no populated cell contains a positive numeric `_G<number>` suffix
- **THEN** the teacher sees that no group has successfully buzzed

#### Scenario: Unauthorized user requests sheet statistics
- **WHEN** a student requests the Sheet statistics operation
- **THEN** the API rejects the request without reading the spreadsheet
