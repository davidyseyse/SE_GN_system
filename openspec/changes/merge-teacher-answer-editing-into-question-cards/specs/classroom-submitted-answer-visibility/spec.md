## Purpose

Extend current classroom answer visibility with structured group attribution and readable package-answer rendering.

## MODIFIED Requirements

### Requirement: Active answers expose explicit group attribution

The current-answer projection SHALL return the answer's persisted group number independently from student display identity and SHALL continue to hide internal round and submission timestamp metadata.

#### Scenario: Current student answer is rendered

- **WHEN** a classroom member loads a current answer with a group snapshot
- **THEN** the question card displays the answer and that group without parsing the group from the student's display name

#### Scenario: Legacy answer has no group snapshot

- **WHEN** a legacy current answer has no safely derived group
- **THEN** the card presents the group as unspecified rather than inventing an attribution

### Requirement: Active package answers are human-readable

The classroom question card SHALL render item-keyed answers using the current question-item prompts rather than raw JSON or UUID keys.

#### Scenario: Classroom member views a package answer

- **WHEN** the current answer contains values keyed by question-item identifiers
- **THEN** each recognized value is displayed beside its item prompt in ordinal order

