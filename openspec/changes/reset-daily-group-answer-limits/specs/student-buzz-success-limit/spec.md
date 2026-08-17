## MODIFIED Requirements

### Requirement: System enforces one completed buzz win per group each day

The system SHALL enforce a fixed completed buzz-win limit of one for each positive group number across all classrooms during each Asia/Taipei calendar day. Teachers SHALL NOT configure this limit when creating or resuming a classroom. An authenticated teacher or assistant MAY explicitly reset every group's usage for the current Taipei day, after which each group MAY complete one additional successful answer without deleting its retained answer history.

#### Scenario: Taipei calendar day changes

- **WHEN** Taipei local time passes midnight after a group reached its prior-day limit
- **THEN** that group's current-day completed-success count is zero
- **AND** the group can complete one winning answer during the new day

#### Scenario: Authorized staff resets usage during the day

- **WHEN** an authenticated teacher or assistant resets all groups' current-day answer-limit usage
- **THEN** every group can complete one subsequent winning answer during the same Taipei day
- **AND** successful answers recorded before the reset remain in history

