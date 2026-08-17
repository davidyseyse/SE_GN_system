## Purpose

Defines deterministic generation of question packages from Google Sheets marker cells and same-position answer bindings selected by a teacher.

## ADDED Requirements

### Requirement: Teacher explicitly initiates marker scanning
The system SHALL scan a worksheet only after an authorized teacher selects the source and answer Google Sheet configurations and worksheets and invokes the automatic-generation action.

#### Scenario: Selection alone does not scan
- **WHEN** a teacher changes any source or answer selection
- **THEN** the system does not scan or create questions until the teacher invokes `自動偵測生成題目`

#### Scenario: Teacher starts a scan
- **WHEN** the teacher invokes automatic generation with valid selections
- **THEN** the system reads the selected source worksheet and returns a preview without persisting questions

### Requirement: Scanner recognizes only the fixed marker grammar
The system MUST recognize trimmed, case-insensitive cell values matching `[[Qn]]` or `[[Qn-m]]`, where each numeric component is a positive integer, and MUST ignore surrounding text, images, formatting, notes, and unrelated cell values.

#### Scenario: Multi-item markers are grouped
- **WHEN** the source contains `[[Q1-1]]` and `[[Q1-2]]`
- **THEN** the preview contains package `Q1` with default item descriptions `Q1-1` and `Q1-2`

#### Scenario: Standalone marker creates one-item package
- **WHEN** the source contains `[[Q2]]`
- **THEN** the preview contains package title `Q2` with one item whose default description is `Q2`

#### Scenario: Images are present
- **WHEN** the worksheet contains in-cell or floating images and valid markers exist in other cell values
- **THEN** the images do not prevent marker detection and are not imported into question content

#### Scenario: Invalid text resembles a marker
- **WHEN** a cell contains `Q1-1`, `[[Q0]]`, `[[Q1-0]]`, or text surrounding `[[Q1]]`
- **THEN** the system does not treat that cell as a valid marker

### Requirement: Detected cells map to the same answer coordinate
The system SHALL map every detected source A1 coordinate to the identical A1 coordinate in the independently selected answer spreadsheet and worksheet without presenting a mapping-mode control.

#### Scenario: Source and answer spreadsheets differ
- **WHEN** `Source/Template!B2` contains `[[Q1-1]]` and the teacher selects `Destination/Answers`
- **THEN** the preview binds item `Q1-1` to `Destination/Answers!B2`

#### Scenario: Source and answer worksheet are identical
- **WHEN** the teacher selects the same spreadsheet and worksheet for source and answer
- **THEN** the system permits the selection and maps every marker cell to itself

### Requirement: Preview is editable and confirmable
The system SHALL show detected packages before creation and SHALL let the teacher edit package and item descriptions or exclude a detected package.

#### Scenario: Teacher changes generated descriptions
- **WHEN** the teacher edits a preview title or item description and confirms
- **THEN** the created package uses the edited text while retaining the detected marker identity and binding

#### Scenario: Teacher excludes a package
- **WHEN** the teacher deselects one detected package and confirms the scan
- **THEN** the system creates the remaining selected packages and does not create the excluded package

### Requirement: Scan and confirmation validate both spreadsheets
The system MUST verify source readability, destination writability, worksheet existence, marker consistency, destination occupancy, and existing bindings before creation.

#### Scenario: Destination contains ordinary data
- **WHEN** the mapped answer cell is nonblank and is not the same physical marker cell being imported
- **THEN** the preview identifies the conflict and confirmation does not overwrite the data

#### Scenario: Same physical marker cell is the destination
- **WHEN** source and answer resolve to the same cell containing the detected marker
- **THEN** the marker does not count as conflicting destination data

#### Scenario: Marker is duplicated
- **WHEN** the same normalized marker appears more than once in a scan
- **THEN** the system reports a blocking duplicate-marker error and creates no packages

#### Scenario: Subquestion numbering has a gap
- **WHEN** a package contains `[[Q1-1]]` and `[[Q1-3]]` but no `[[Q1-2]]`
- **THEN** the system preserves both items and reports a nonblocking numbering warning

### Requirement: Generated bindings retain provenance and prevent duplicates
The system MUST retain the source spreadsheet configuration, worksheet, cell, and marker separately from the answer spreadsheet configuration, worksheet, and target cell.

#### Scenario: Marker is overwritten by an answer
- **WHEN** a same-cell generated item later writes a student answer over its marker
- **THEN** the stored provenance remains available to teachers and audit behavior does not depend on the marker remaining in Google Sheets

#### Scenario: Previously imported source is scanned again
- **WHEN** a scan finds a source position already associated with an active generated item
- **THEN** the preview identifies it as already imported and confirmation does not create a duplicate

#### Scenario: Destination is already bound
- **WHEN** a detected item maps to an answer position used by another active item
- **THEN** the system rejects that binding without changing existing questions

### Requirement: Confirmed generation is atomic
The system MUST create every selected package, item, provenance record, and binding in one database transaction after revalidating the scan.

#### Scenario: Confirmation succeeds
- **WHEN** all selected preview entries remain valid at confirmation time
- **THEN** all selected packages are created in `READY` state with their ordered items and bindings

#### Scenario: Source changes before confirmation
- **WHEN** a marker or destination changes after scanning and before confirmation
- **THEN** confirmation fails without partially creating the selected batch

### Requirement: Spreadsheet binding details are teacher-only
The system MUST NOT expose source or answer spreadsheet configurations, worksheet names, markers, or cell positions through student APIs, student realtime events, or student UI.

#### Scenario: Student views a generated package
- **WHEN** a student receives or displays a generated package
- **THEN** the student sees only classroom interaction fields, package title, and item descriptions, with no spreadsheet provenance or binding details

