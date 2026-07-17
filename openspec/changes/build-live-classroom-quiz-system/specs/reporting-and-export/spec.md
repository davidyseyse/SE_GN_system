## ADDED Requirements

### Requirement: Teachers can view classroom result summaries
The system SHALL provide teachers with classroom-level summaries including participation, submission counts, correctness statistics, buzz history, and score distribution.

#### Scenario: Teacher opens classroom summary
- **WHEN** a teacher requests the summary view for a classroom
- **THEN** the system returns aggregated metrics for participation, answers, buzzes, and scores

#### Scenario: Question statistics are available
- **WHEN** a teacher requests analytics for a specific question
- **THEN** the system returns option distribution, answer counts, correctness rate, and question status details

### Requirement: Teachers can export classroom data
The system SHALL allow teachers to export classroom results, student scores, and buzz or submission records in machine-readable file formats.

#### Scenario: CSV export is generated
- **WHEN** a teacher requests a CSV export for a classroom result set
- **THEN** the system generates and returns a CSV file containing the selected report data

#### Scenario: Unauthorized export is rejected
- **WHEN** a non-teacher requests a classroom export
- **THEN** the system denies the request and does not generate an export file

### Requirement: Exported data is traceable
The system SHALL record who generated an export and which classroom dataset was included for audit purposes.

#### Scenario: Export action is audited
- **WHEN** a teacher successfully generates a classroom export
- **THEN** the system writes an audit record containing the teacher identity, classroom identity, export type, and generation timestamp
