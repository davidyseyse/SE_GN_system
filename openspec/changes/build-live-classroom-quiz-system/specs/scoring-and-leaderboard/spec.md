## ADDED Requirements

### Requirement: Scores are calculated from configurable rules
The system SHALL calculate classroom scores using question score, buzz bonus, timeout handling, and optional wrong-answer penalty settings.

#### Scenario: Score is added for correct answer
- **WHEN** a graded submission matches the scoring rules for the question
- **THEN** the system updates the student's score record with the awarded points

#### Scenario: Wrong-answer penalty is applied
- **WHEN** a classroom enables wrong-answer penalties and a student submits an incorrect answer
- **THEN** the system subtracts the configured penalty from the student's score record

### Requirement: Leaderboards reflect latest scoring state
The system SHALL maintain a leaderboard for the active classroom and publish updated rankings after score-affecting events.

#### Scenario: Leaderboard updates after scoring
- **WHEN** a submission, buzz bonus, or teacher review changes a student's total score
- **THEN** the system recalculates affected rankings and publishes a leaderboard update event

#### Scenario: Student sees permitted ranking view
- **WHEN** a student opens the ranking view for a classroom where ranking visibility is enabled
- **THEN** the system returns the current ranking data permitted by classroom visibility settings

### Requirement: Teachers can inspect score breakdowns
The system SHALL allow a teacher to inspect per-student and per-question score details for the classroom.

#### Scenario: Teacher opens score detail
- **WHEN** a teacher requests a student's score detail
- **THEN** the system returns the question-level score contributions, buzz bonuses, penalties, and review adjustments

#### Scenario: Unauthorized score access is blocked
- **WHEN** a student requests another student's score detail without permission
- **THEN** the system denies the request and does not disclose private score data
