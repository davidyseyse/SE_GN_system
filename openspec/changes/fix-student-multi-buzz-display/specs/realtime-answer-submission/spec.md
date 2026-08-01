## ADDED Requirements

### Requirement: Students can interact with multiple active questions independently
The system SHALL allow authorized students to manage answer input and submission separately for each concurrently active question shown in the student buzz lobby.

#### Scenario: Student submits answers on different open questions
- **WHEN** a student has won or may answer on question A and question B at the same time
- **THEN** the system accepts a submission for question A without clearing or blocking the answer draft for question B

#### Scenario: Submission state is tracked per question
- **WHEN** a student submits an answer for question A
- **THEN** the student client marks question A as submitted while leaving the submission state of other active questions unchanged

#### Scenario: Answer submission on one question does not affect another question's buzz state
- **WHEN** a student submits an answer for question 1 while question 2 is in `BUZZ_OPEN`
- **THEN** the student client updates submission state for question 1 only and leaves question 2's buzz availability and interaction state unchanged

## MODIFIED Requirements

### Requirement: Students can submit answers during the answer window
The system SHALL allow authorized students to submit one answer per active question during the configured answer window, subject to classroom rules for resubmission.

#### Scenario: Answer submitted within time limit
- **WHEN** a student submits an answer while the question is in `ANSWER_OPEN`
- **THEN** the system stores the submission, acknowledges receipt, and updates the student submission state for that question only

#### Scenario: Late answer is rejected
- **WHEN** a student submits an answer after the question has been locked
- **THEN** the system rejects the submission and preserves the locked state for that question without affecting other active questions
