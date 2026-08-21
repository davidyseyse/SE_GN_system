## Why

The current fixed global limit of one completed buzz win per group per Asia/Taipei day can leave later question cells without an answer even when students successfully buzzed and submitted answers. A six-question activity with three participating groups can therefore produce only three successful answers.

The classroom instead needs a first-win priority: groups that have not yet succeeded today should be favored first, while questions whose submitted candidates have all already succeeded should still select the fastest candidate. This preserves an initial opportunity for every participating group without preventing the remaining questions from producing answers.

## What Changes

- **BREAKING** Replace the fixed one-success-per-day rejection rule with first-win prioritization.
- For each finalized question, first consider submitted candidates whose group has zero current-day successes and select the fastest among them.
- If the question has no submitted candidate with zero current-day successes, select the fastest submitted candidate regardless of prior success count.
- Increment the winning group's dated success count without imposing a maximum.
- Stop rejecting buzz attempts or submitted answers solely because a group has already succeeded that day.
- Serialize competing question finalizations so concurrent rounds observe an authoritative success count and cannot accidentally grant duplicate first-win priority from the same starting state.
- Preserve Asia/Taipei daily scoping and the teacher's current-day counter reset operation.
- Continue writing exactly one winning answer per finalized question to Google Sheets; a question remains unanswered only when it has no valid submitted candidate.
- Update teacher-facing usage from a `count / 1` limit display to an accumulated daily-success count.

## Capabilities

### New Capabilities

- `first-win-priority-ranking`: Cross-question winner selection that prioritizes each participating group's first daily success and falls back to fastest-submission ranking afterward.

### Modified Capabilities

- `student-buzz-success-limit`: Replace the fixed daily hard limit and `SUCCESS_LIMIT_REACHED` rejection behavior with unbounded dated success accounting used for ranking priority.

## Impact

- API buzz registration and round-finalization policy.
- PostgreSQL transaction and concurrency behavior for global group counters.
- Buzz/submission outcomes and API payloads that currently expose `successfulBuzzLimit`.
- Teacher member-count labels and student neutral-result handling.
- Google Sheets winner synchronization for questions after every group has already succeeded.
- Unit, integration, concurrency, web, and six-question scenario coverage.
