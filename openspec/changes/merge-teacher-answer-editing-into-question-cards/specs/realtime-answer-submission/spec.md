## Purpose

Extend realtime answer reconciliation to teacher changes of answer content and group attribution.

## MODIFIED Requirements

### Requirement: Teacher answer changes invalidate current views

The system SHALL notify connected classroom clients after an authorized teacher or assistant creates or updates a current-round answer, including changes limited to group attribution.

#### Scenario: Teacher updates answer content or group

- **WHEN** the mutation commits successfully
- **THEN** connected clients refetch the affected question's authoritative current answers and display the updated answer and group

#### Scenario: Mutation fails

- **WHEN** answer validation, authorization, persistence, or synchronization preparation fails before commit
- **THEN** the system does not publish a successful answer-change state and the editor retains the entered values for retry

