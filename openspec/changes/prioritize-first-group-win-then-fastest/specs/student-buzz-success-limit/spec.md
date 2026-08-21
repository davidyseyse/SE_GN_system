## MODIFIED Requirements

### Requirement: System records unbounded daily group successes for prioritization

The system SHALL maintain each positive group number's completed buzz-win count across all classrooms for the current Asia/Taipei calendar day. The count SHALL be used to prioritize groups that have not yet completed a success, but SHALL NOT act as a hard eligibility limit. An authenticated teacher or assistant MAY explicitly reset every group's current-day count without deleting retained answer history.

#### Scenario: Group has already completed a winning answer

- **WHEN** a group that already has one or more current-day successes records another valid buzz and submits an answer
- **THEN** the system retains the attempt and answer as a valid candidate
- **AND** it does not return `SUCCESS_LIMIT_REACHED`

#### Scenario: Previously successful group wins through fastest fallback

- **WHEN** a previously successful group wins a question for which no submitted candidate group has zero current-day successes
- **THEN** the system atomically increments that group's current-day success count
- **AND** persists and synchronizes the winning answer normally

#### Scenario: Taipei calendar day changes

- **WHEN** Taipei local time passes midnight
- **THEN** every group's effective current-day success count is zero
- **AND** each group is eligible for first-win priority again

#### Scenario: Authorized staff resets usage during the day

- **WHEN** an authenticated teacher or assistant resets all groups' current-day success counts
- **THEN** every group is eligible for first-win priority again
- **AND** successful answers recorded before the reset remain in history

### Requirement: Group success is counted only after winner persistence

The system SHALL increment the winning group's dated global success count only in the same database transaction that marks the accepted fastest eligible answer as the question winner. A buzz click, a skipped or missing submission, a failed transaction, and a Google Sheets retry SHALL NOT increment the count.

#### Scenario: Winner answer commits

- **WHEN** the selected candidate's answer is finalized successfully
- **THEN** the winner state and incremented current-day group success count commit atomically

#### Scenario: Winner persistence fails

- **WHEN** winner persistence fails
- **THEN** the group success-count increment is rolled back

#### Scenario: Google Sheets fails after commit

- **WHEN** winner state and success count commit but Google Sheets synchronization fails
- **THEN** the success remains counted exactly once
- **AND** retrying the Sheets write does not increment the success count again

### Requirement: Current-day group success totals are visible without a limit

The system SHALL expose each student's group number and authoritative current-day completed-success count to authorized teacher views. User interfaces SHALL describe the value as an accumulated count and SHALL NOT present it as usage against a maximum of one.

#### Scenario: Teacher views classroom members

- **WHEN** a teacher or assistant loads the classroom member list
- **THEN** each student entry includes its group number and that group's current-day global completed-success count
- **AND** the interface displays the count without `/ 1` or a reached-limit state

## REMOVED Requirements

### Requirement: A group reaching the daily global limit skips same-day writes

**Reason**: Prior success now changes ranking priority rather than making the group ineligible; rejecting later answers prevents all questions from receiving a winning answer.

**Migration**: Remove `SUCCESS_LIMIT_REACHED` checks from buzz registration and finalization. Existing dated counts remain valid and become ranking inputs. Remove or deprecate `successfulBuzzLimit` response fields and update clients to render the accumulated count.

### Requirement: Student feedback does not reveal the group-limit reason

**Reason**: The group-limit rejection outcome no longer exists. Neutral feedback remains appropriate for other unsuccessful outcomes but no longer needs to mask `SUCCESS_LIMIT_REACHED`.

**Migration**: Remove the obsolete outcome mapping while retaining neutral messages for non-winning or invalid submissions.
