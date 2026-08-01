## Purpose

Defines the student-facing presentation and interaction model for question packages while keeping realtime state synchronization hidden from the visible event-feed interface.

## ADDED Requirements

### Requirement: Student sees complete interactive question packages
The system SHALL present each visible question package with its title, ordered subquestions, package state, and one package-level buzz action.

#### Scenario: Package becomes visible
- **WHEN** a package enters a student-visible state
- **THEN** the student sees the package title and all ordered subquestion prompts permitted by publication settings

#### Scenario: Package is open for buzzing
- **WHEN** a visible package is accepting buzzes
- **THEN** the student sees one enabled buzz action for the package and no independent subquestion buzz actions

#### Scenario: Student wins the package buzz
- **WHEN** the current student wins the package round
- **THEN** the interface enables one answer input for every subquestion in that package

### Requirement: Visible realtime message feed is removed
The student interface SHALL NOT display a realtime event-history or realtime-message panel.

#### Scenario: Student opens the live classroom
- **WHEN** the live classroom contains realtime events
- **THEN** the interface does not render an event list containing event names, timestamps, or raw event history

### Requirement: Realtime interaction remains operational
The system MUST retain realtime connectivity and event-driven package updates after removing the visible message feed.

#### Scenario: Package state changes without manual refresh
- **WHEN** the client receives an authorized package state, buzz, reset, or submission event
- **THEN** the visible affected package updates without requiring the student to reload the page

#### Scenario: Realtime connection status is shown
- **WHEN** the student's realtime connection state changes
- **THEN** the existing connection-status indicator may continue to show the current connection state without exposing event history

