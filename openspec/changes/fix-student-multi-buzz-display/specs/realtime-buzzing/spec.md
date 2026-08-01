## ADDED Requirements

### Requirement: Student clients display all concurrently open buzz questions
The system SHALL display every question in a student classroom session that is currently in an interactive buzz or answer state, rather than only the first matching question.

#### Scenario: Multiple buzz questions are visible
- **WHEN** a teacher has opened buzz for more than one question in the same classroom
- **THEN** the student buzz lobby lists every question currently in `BUZZ_OPEN`, `ANSWER_OPEN`, `LOCKED`, or `PUBLISHED`

#### Scenario: Single buzz question remains supported
- **WHEN** only one question is in an interactive state
- **THEN** the student buzz lobby displays exactly that one question with the same buzz and answer interactions as before

#### Scenario: First opened buzz question appears immediately
- **WHEN** a teacher opens buzz for question 1 and no other question is in an interactive state
- **THEN** the student buzz lobby displays question 1 with buzz actions enabled for that question

#### Scenario: Second opened buzz question appears alongside the first
- **WHEN** question 1 is already in `BUZZ_OPEN` and a teacher opens buzz for question 2
- **THEN** the student buzz lobby displays both question 1 and question 2 at the same time

#### Scenario: Closed question is removed without affecting others
- **WHEN** question 1 is in an interactive state, question 2 is also in an interactive state, and a teacher closes question 2
- **THEN** the student buzz lobby removes question 2 from the display while question 1 remains visible and fully interactive

### Requirement: Buzz interactions are isolated per question
The system SHALL evaluate buzz availability, winner state, and lock state independently for each displayed question using that question's identifier.

#### Scenario: Student buzzes on one of several open questions
- **WHEN** a student submits a buzz for question A while question B is also in `BUZZ_OPEN`
- **THEN** the buzz action applies only to question A and does not change the buzz availability of question B

#### Scenario: Winner state is scoped by question
- **WHEN** a successful buzz result event is received for question A
- **THEN** the student client updates the winner display and lock state for question A only

#### Scenario: Buzz on one question does not change another question's buzz state
- **WHEN** a student submits a buzz for question 1 while question 2 is also in `BUZZ_OPEN`
- **THEN** the buzz availability, winner display, and lock state of question 2 remain unchanged

## MODIFIED Requirements

### Requirement: Buzz results are synchronized in real time
The system SHALL publish buzz window state changes and successful buzz outcomes to authorized classroom clients in near real time.

#### Scenario: Buzz opens for students
- **WHEN** a teacher opens a buzz-enabled question
- **THEN** the system publishes a buzz-open event so student clients can enable the buzz action for that question without hiding other already-open buzz questions

#### Scenario: Winner is announced
- **WHEN** a first successful buzzer is determined
- **THEN** the system publishes the winner and lock state to authorized classroom clients without requiring manual refresh, and student clients reflect the winner on the corresponding question card
