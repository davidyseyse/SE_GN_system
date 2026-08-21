## ADDED Requirements

### Requirement: Questions prioritize a candidate's first daily group success

When finalizing a buzz question, the system SHALL consider valid candidates who have both an active buzz attempt and a persisted pending answer for the current round. If one or more candidate groups have zero completed successes for the current Asia/Taipei calendar day, the system SHALL select the fastest candidate among only those zero-success groups.

#### Scenario: All candidate groups have zero successes

- **WHEN** groups 1, 2, and 3 submit for a question in that receipt order and each group has zero current-day successes
- **THEN** group 1 wins the question
- **AND** group 1's current-day success count becomes one

#### Scenario: Faster group has already succeeded

- **WHEN** groups 1, 2, and 3 submit for a question in that receipt order
- **AND** group 1 already has one or more current-day successes while groups 2 and 3 have zero
- **THEN** group 2 wins the question
- **AND** group 1's faster receipt does not override group 2's first-win priority

### Requirement: Questions fall back to the fastest submitted candidate

If a finalized question has no valid candidate from a zero-success group, the system SHALL select the valid candidate with the earliest server receipt order regardless of previous success count. Prior success SHALL NOT disqualify a buzz attempt or answer submission.

#### Scenario: Every candidate group has already succeeded

- **WHEN** groups 1, 2, and 3 submit for a question in that receipt order
- **AND** all three groups have at least one current-day success
- **THEN** group 1 wins as the fastest candidate
- **AND** its current-day success count increments without a maximum

#### Scenario: Only previously successful groups participate

- **WHEN** a question receives a valid submission only from a group that has already succeeded today
- **THEN** that group wins the question
- **AND** the question is not left unanswered because of the prior success

#### Scenario: No candidate submits an answer

- **WHEN** a question is finalized without any valid pending submission
- **THEN** the round reports no eligible submission
- **AND** the system does not create a winner or write an answer to Google Sheets

### Requirement: First-win prioritization is consistent across concurrent questions

The system SHALL serialize the policy decision and dated counter update for concurrently finalized questions so each decision observes all earlier committed successes. The winner selection and success-count increment SHALL commit atomically with the winning submission state.

#### Scenario: Two questions finalize concurrently

- **WHEN** two questions containing the same zero-success fastest group are finalized concurrently
- **THEN** only the first serialized decision treats that group as having zero successes
- **AND** the later decision applies first-win priority using the updated counts

### Requirement: Six-question three-group activity produces a defined allocation

For questions finalized in question order, when groups 1, 2, and 3 submit to every question in that receipt order and begin with zero current-day successes, the first three questions SHALL be awarded to groups 1, 2, and 3 respectively, and subsequent questions SHALL be awarded to group 1 as the fastest candidate.

#### Scenario: Six questions use identical submission order

- **WHEN** six questions are finalized from question 1 through question 6
- **AND** every question has valid submissions ordered group 1, group 2, then group 3
- **AND** all groups initially have zero current-day successes
- **THEN** the winning groups are 1, 2, 3, 1, 1, and 1
- **AND** the final success counts are four for group 1 and one each for groups 2 and 3
- **AND** every question writes its winning answer to Google Sheets

### Requirement: Question finalization order defines cross-question allocation

The system SHALL apply current-day group counts at the time each question finalization is serialized. It SHALL NOT infer cross-question priority from question number or compare receipt orders belonging to different questions.

#### Scenario: Questions finalize out of numeric order

- **WHEN** question 6 finalizes before question 1
- **THEN** question 6 consumes the first applicable priority decision
- **AND** question 1 later uses the counts produced by the earlier finalization
