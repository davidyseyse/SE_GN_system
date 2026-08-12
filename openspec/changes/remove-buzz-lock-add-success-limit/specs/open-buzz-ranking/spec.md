## ADDED Requirements

### Requirement: Buzz registration is non-exclusive
While a buzz round is open, the system SHALL allow every eligible classroom student to register one or more independently identified buzz attempts without acquiring or observing an exclusive winner lock. A previously recorded attempt or selected fastest student MUST NOT disable another eligible student's buzz action.

#### Scenario: Multiple students buzz during the same round
- **WHEN** multiple eligible students send buzz requests for the same open question round
- **THEN** the system records each request with its own request ID, server receipt order, and receipt timestamp
- **AND** each response reports that the attempt was recorded without representing a lock acquisition

#### Scenario: Student buzzes after an earlier attempt exists
- **WHEN** an eligible student buzzes after another student's attempt has already been recorded
- **THEN** the later attempt is still accepted and persisted
- **AND** the student interface does not disable the buzz action merely because another attempt exists

### Requirement: Fastest active attempt controls the authoritative answer
The system SHALL define the fastest student as the student owning the lowest server receipt order among active attempts in the current round. Only that student's submission SHALL be persisted as the buzz winner submission; other submissions SHALL return a skipped outcome without creating a submission.

#### Scenario: Fastest student submits
- **WHEN** the student with the lowest active receipt order submits a complete valid answer
- **THEN** the system persists exactly one winner submission for the round
- **AND** marks that student's buzz attempt successful

#### Scenario: Slower student submits first
- **WHEN** a student whose active receipt order is not the lowest submits before the fastest student
- **THEN** the system returns a `NOT_FASTEST` skipped outcome
- **AND** does not persist the answer, award score, increment a success count, or update Google Sheets

#### Scenario: Earlier attempt is cancelled
- **WHEN** the lowest-order attempt is cancelled before it produces a submission
- **THEN** the next-lowest active and non-expired attempt becomes eligible to submit

#### Scenario: Concurrent submissions race
- **WHEN** concurrent requests attempt to create a winner submission for one question round
- **THEN** a database invariant permits at most one winner submission
- **AND** every losing request returns a skipped outcome without side effects

### Requirement: Students prepare answers before buzzing
While a buzz round is open, the system SHALL show the answer controls before a student registers a buzz. The buzz action SHALL remain disabled until every required answer is non-empty. Activating the buzz action SHALL record the attempt and immediately submit the prepared answers.

#### Scenario: Open round has incomplete answers
- **WHEN** one or more required answer fields are empty
- **THEN** the student can edit every answer field
- **AND** the buzz action remains disabled

#### Scenario: Student completes answers and buzzes
- **WHEN** every required answer is complete and the student activates the buzz action
- **THEN** the client records the buzz attempt
- **AND** immediately submits the prepared answers for fastest-attempt evaluation

#### Scenario: Buzz registration fails
- **WHEN** the prepared-answer buzz request is rejected or fails
- **THEN** no answer submission is attempted
- **AND** the prepared answers remain available for correction or retry

### Requirement: Teacher and student use separate login pages
The web client SHALL provide distinct teacher and student login routes without a role selector or a link to the other audience's login page. The application header SHALL show only the login action matching the current route's audience.

#### Scenario: Teacher opens teacher login
- **WHEN** a user visits `/teacher/login`
- **THEN** only administrator account and password controls are shown

#### Scenario: Student opens student login
- **WHEN** a user visits `/student/login`
- **THEN** only student ID, group number, and an existing-classroom selector are shown

#### Scenario: User views an audience-specific page
- **WHEN** a user views a teacher or student page
- **THEN** the header shows only that audience's login action
- **AND** the login form does not link to the other audience's login page

### Requirement: Student selects an existing classroom
The student login SHALL load existing classrooms into a selector instead of accepting a manually entered classroom code. No classroom SHALL be selected by default, and joining SHALL remain disabled until the student provides a valid student ID and group number and selects a classroom.

#### Scenario: Student opens the login page
- **WHEN** the classroom list loads
- **THEN** each existing classroom is displayed using its name and code
- **AND** the initial option asks the student to select a classroom

#### Scenario: Student joins a selected classroom
- **WHEN** the student enters a valid student ID and positive group number and selects a classroom
- **THEN** the system authenticates the student and joins the selected classroom through the existing membership endpoint

### Requirement: Google Sheets receives only the fastest accepted answer
The system SHALL synchronize Google Sheets only after the fastest eligible student's answer has been committed. Scalar and package answers SHALL retain their configured cell mapping and group suffix formatting.

#### Scenario: Accepted winner synchronization
- **WHEN** the fastest eligible answer is committed
- **THEN** the system writes that answer to the configured scalar cell or package cells
- **AND** records the existing synchronized or retryable status on the persisted winner submission

#### Scenario: Skipped submission does not synchronize
- **WHEN** a submission is skipped because it is not fastest, has exceeded the success limit, has no active attempt, or duplicates an accepted winner
- **THEN** the system does not call Google Sheets and does not create a sheet-sync record

### Requirement: Buzz attempts have no individual answer deadline
The system SHALL NOT attach or enforce an individual answer deadline after a student registers a buzz attempt. A recorded attempt SHALL remain active until it produces the accepted answer, the student cancels it, or the teacher resets or closes the round.

#### Scenario: Student keeps working after the former limit duration
- **WHEN** a student has not submitted after the question's legacy answer-time-limit duration
- **THEN** the student's attempt remains active and eligible
- **AND** the interface shows no countdown or timeout message

#### Scenario: Student cancels an attempt
- **WHEN** a student cancels their own active unsubmitted attempt
- **THEN** only that student's attempt becomes inactive
- **AND** no winner lock is released because no winner lock exists
