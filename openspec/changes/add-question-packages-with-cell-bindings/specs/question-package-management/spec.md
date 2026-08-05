## Purpose

Defines how teachers compose, validate, persist, and manage titled question packages containing one or more ordered subquestions with individual Google Sheets cell bindings.

## ADDED Requirements

### Requirement: Teacher can compose a question package
The system SHALL provide an authorized teacher with a package authoring form ordered as sheet configuration, package title, subquestions, add-subquestion action, and create action.

#### Scenario: Form starts with one subquestion
- **WHEN** the teacher opens or resets the package authoring form
- **THEN** the system displays exactly subquestion 1 with a prompt field and target-cell field and does not display a pre-created subquestion 2

#### Scenario: Teacher appends a subquestion
- **WHEN** the teacher invokes the add-subquestion action
- **THEN** the system inserts one new sequentially numbered subquestion immediately before the add-subquestion action

#### Scenario: Teacher removes an appended subquestion
- **WHEN** the teacher removes subquestion 2 or any later subquestion
- **THEN** the system removes that subquestion and renumbers the remaining displayed subquestions consecutively

#### Scenario: First subquestion cannot be removed
- **WHEN** the package contains one or more subquestions
- **THEN** the system does not offer a removal action for subquestion 1 and always retains at least one subquestion

### Requirement: Package uses one sheet configuration and per-subquestion cells
The system SHALL associate one selected Google Sheets configuration with the package and SHALL associate a distinct target-cell value with each subquestion.

#### Scenario: Different cells are bound within one package
- **WHEN** a teacher creates a package whose subquestions specify different valid target cells
- **THEN** the system stores the shared sheet configuration and preserves each prompt-to-cell binding in subquestion order

#### Scenario: Duplicate target cells are rejected
- **WHEN** two subquestions in the same package specify the same normalized target cell
- **THEN** the system rejects package creation and identifies the duplicate binding without persisting any part of the package

### Requirement: Complete package input is validated
The system MUST require a nonblank package title, a valid sheet configuration, at least one subquestion, and a nonblank prompt and valid single-cell target for every subquestion.

#### Scenario: Valid package can be submitted
- **WHEN** all required package and subquestion values are valid
- **THEN** the create action is available and the client submits the entire package in one request

#### Scenario: Incomplete subquestion is rejected
- **WHEN** any subquestion has a blank prompt or blank or invalid target cell
- **THEN** the system rejects the request and identifies the invalid subquestion without persisting any part of the package

#### Scenario: Unknown sheet configuration is rejected
- **WHEN** the request refers to a sheet configuration that does not exist
- **THEN** the system rejects the request without persisting any part of the package

### Requirement: Package creation is atomic
The system MUST create the package and all ordered subquestions as one atomic operation.

#### Scenario: All records are created
- **WHEN** a valid package request containing one or more subquestions succeeds
- **THEN** the response returns the package and every persisted subquestion in the requested order

#### Scenario: One subquestion cannot be persisted
- **WHEN** any package or subquestion record fails to persist
- **THEN** the system rolls back the complete operation and no partial package or subquestion remains

### Requirement: Successful creation resets the authoring form
The system SHALL clear package draft input only after successful creation.

#### Scenario: Package creation succeeds
- **WHEN** the server confirms successful package creation
- **THEN** the form returns to an empty title, the retained sheet-selection behavior, and exactly one empty non-removable subquestion

#### Scenario: Package creation fails
- **WHEN** package creation is rejected or fails
- **THEN** the form preserves the teacher's title, sheet selection, and all subquestion input for correction or retry

