## Purpose

Extends teacher package management with generated-package provenance and role-specific visibility.

## MODIFIED Requirements

### Requirement: Package management distinguishes authoring provenance from answer binding
The system SHALL retain answer spreadsheet configuration and per-item target cells for every generated package and SHALL additionally retain its marker-detection source without changing manual package behavior.

#### Scenario: Teacher views a generated package
- **WHEN** an authorized teacher views a generated package
- **THEN** the package management response may show the source spreadsheet/worksheet/cell and answer spreadsheet/worksheet/cell for each item

#### Scenario: Teacher views a manual package
- **WHEN** an authorized teacher views a manually created package
- **THEN** the package remains manageable without requiring marker-source provenance

#### Scenario: Student views either package type
- **WHEN** a student views a generated or manual package
- **THEN** the response omits all spreadsheet configuration and cell-binding details

