## MODIFIED Requirements

### Requirement: The system records and reports scores without a leaderboard

The system SHALL preserve score calculation, score records, score detail, and score exports while providing no teacher or student leaderboard interface.

#### Scenario: Answer or buzz awards points

- **WHEN** existing scoring policy awards or reverses points for an answer, review, deletion, or buzz action
- **THEN** the system writes the same score records and running totals as before without requiring ranking calculation or leaderboard publication

#### Scenario: Authorized user requests retained score detail or export

- **WHEN** an authorized user requests a supported score detail or reporting operation
- **THEN** the system derives the response from retained score records without depending on leaderboard snapshots

### Requirement: Leaderboard presentation and dependencies are removed

The system SHALL NOT display, query, publish, calculate, or persist leaderboard rankings.

#### Scenario: Teacher or student opens a classroom

- **WHEN** the classroom interface loads
- **THEN** no leaderboard panel, ranking request, or leaderboard visibility control is presented

#### Scenario: A score changes

- **WHEN** scoring creates a new score record
- **THEN** no leaderboard-specific realtime event or snapshot is produced

#### Scenario: Classroom is created

- **WHEN** a teacher creates a classroom
- **THEN** classroom creation does not require a user-selected leaderboard visibility setting

## REMOVED Requirements

### Requirement: Users can view a ranked leaderboard

**Reason:** Ranking presentation is no longer part of the classroom experience.

**Migration:** Remove teacher and student ranking panels, client queries, the leaderboard endpoint/service, ranking snapshots, visibility configuration, and their tests/documentation while retaining score records and reporting.

