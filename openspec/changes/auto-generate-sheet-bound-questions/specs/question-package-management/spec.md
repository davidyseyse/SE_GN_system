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

### Requirement: Deleting a Sheet configuration removes dependent packages atomically
The system SHALL require teacher confirmation before deleting a Sheet configuration and SHALL delete every package that uses the configuration as an answer binding or generated source binding in the same transaction as the configuration. The response SHALL report how many dependent packages were deleted.

#### Scenario: Configuration is used by packages
- **WHEN** a teacher confirms deletion of a Sheet configuration referenced by one or more packages
- **THEN** the dependent packages and their cascaded classroom records are deleted before the configuration
- **AND** the teacher sees how many packages were removed

#### Scenario: Configuration deletion fails
- **WHEN** any dependent-package or configuration delete fails
- **THEN** the transaction rolls back without partially deleting packages or the configuration

#### Scenario: Teacher cancels deletion
- **WHEN** the teacher declines the destructive confirmation
- **THEN** the client sends no deletion request and preserves the configuration and packages

### Requirement: Sheet statistics award one point per group per cell
The teacher Sheet-statistics operation SHALL split a cell on `/` or `／`, recognize each segment ending in a positive `_G<number>` suffix, and award each distinct group at most one point for that cell. Points from different cells SHALL accumulate.

#### Scenario: One cell contains answers from different groups
- **WHEN** a cell contains `fdsfs_G1/ asda_G2`
- **THEN** group 1 receives one point and group 2 receives one point

#### Scenario: One group appears repeatedly in one cell
- **WHEN** a cell contains `first_G1/ retry_G1`
- **THEN** group 1 receives only one point for that cell

#### Scenario: One group appears in different cells
- **WHEN** two separate cells each contain a segment ending in `_G1`
- **THEN** group 1 receives two points

