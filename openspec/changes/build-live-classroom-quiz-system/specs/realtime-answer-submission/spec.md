## ADDED Requirements

### Requirement: Students can submit answers during the answer window
The system SHALL allow authorized students to submit one answer per active question during the configured answer window, subject to classroom rules for resubmission.

#### Scenario: Answer submitted within time limit
- **WHEN** a student submits an answer while the question is in `ANSWER_OPEN`
- **THEN** the system stores the submission, acknowledges receipt, and updates the student submission state

#### Scenario: Late answer is rejected
- **WHEN** a student submits an answer after the question has been locked
- **THEN** the system rejects the submission and preserves the locked state

### Requirement: Objective questions are auto-graded
The system SHALL automatically grade single-choice, multiple-choice, and true-false submissions against the stored answer key after valid submission.

#### Scenario: Correct objective answer receives score
- **WHEN** a student submits a correct objective answer
- **THEN** the system marks the submission correct and applies the configured score according to classroom rules

#### Scenario: Incorrect objective answer updates statistics
- **WHEN** a student submits an incorrect objective answer
- **THEN** the system marks the submission incorrect and updates aggregate question statistics

### Requirement: Subjective answers enter teacher review
The system SHALL store short-answer submissions for later teacher review and exclude final subjective scoring until review is completed.

#### Scenario: Short answer is marked pending review
- **WHEN** a student submits a short-answer response during the answer window
- **THEN** the system stores the response with a pending-review status

#### Scenario: Teacher finalizes subjective score
- **WHEN** a teacher reviews and scores a short-answer submission
- **THEN** the system updates the submission outcome, recalculates the student's score, and publishes any affected ranking changes
