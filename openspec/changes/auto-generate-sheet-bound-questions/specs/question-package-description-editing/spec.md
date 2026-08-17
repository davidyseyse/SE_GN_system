## Purpose

Defines safe teacher editing of existing package and subquestion descriptions without changing package structure or spreadsheet bindings.

## ADDED Requirements

### Requirement: Teacher can edit READY package descriptions
The system SHALL provide an `編輯` action for an authorized teacher to update the package title and all item descriptions while the package is in `READY` state.

#### Scenario: Teacher opens the editor
- **WHEN** the teacher invokes `編輯` on a READY package
- **THEN** the system displays editable title and item-description fields initialized with current values

#### Scenario: Teacher saves valid descriptions
- **WHEN** the teacher submits a nonblank title and one nonblank description for every existing item
- **THEN** the system atomically updates the package and item descriptions and returns the updated package

#### Scenario: Teacher cancels editing
- **WHEN** the teacher cancels the editor
- **THEN** the system closes the editor without changing persisted descriptions

### Requirement: Description editing preserves package identity and bindings
The system MUST treat description editing as a text-only operation and MUST preserve package ID, item IDs, item order, marker identity, provenance, source locations, and answer locations.

#### Scenario: Request changes structural data
- **WHEN** an update omits an existing item, duplicates an item ID, includes a foreign item ID, or attempts to change a binding
- **THEN** the system rejects the complete update without changing any description

### Requirement: Active or historical packages cannot be relabeled
The system MUST reject description updates unless the authoritative package state is `READY`.

#### Scenario: Package is active
- **WHEN** a teacher attempts to edit a package in `BUZZ_OPEN`, `ANSWER_OPEN`, or `LOCKED`
- **THEN** the system rejects the update and leaves all descriptions unchanged

#### Scenario: Package has advanced beyond READY
- **WHEN** a teacher attempts to edit a package in a review, published, closed, or archived state
- **THEN** the system rejects the update and leaves historical presentation unchanged

### Requirement: Students receive updated descriptions but no edit metadata
The system SHALL publish or return updated package and item descriptions through normal student package state while withholding editor controls and spreadsheet metadata.

#### Scenario: Student reloads after a valid edit
- **WHEN** a teacher successfully edits a READY package and a student subsequently loads it
- **THEN** the student sees the updated descriptions and does not see the edit action, provenance, or binding locations

