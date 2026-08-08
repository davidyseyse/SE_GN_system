## Why

A student who wins a buzz can currently keep the question locked indefinitely when no answer is submitted, preventing every other student from competing. The existing per-question/package `answerTimeLimitSeconds` value should bound that unanswered winner lock and restore competition automatically.

## What Changes

- Start a server-authoritative answer deadline when a buzz request becomes the active winner, using that question/package's positive `answerTimeLimitSeconds` value as N.
- When the deadline passes without a successfully persisted current-round submission, atomically expire the active winner, release the Redis winner lock, keep the question in `BUZZ_OPEN`, and allow another student to buzz in the same round.
- Reject a late answer after its winner deadline even if background cleanup has not run yet, while ensuring an answer committed before expiration prevents timeout release.
- Recover overdue locks after API restart and make repeated or concurrent timeout processing idempotent.
- Publish a classroom realtime timeout/cancellation notification so student and teacher clients clear the expired winner and immediately show that buzzing is available again.
- Preserve manual winner cancellation, teacher stop/reset, submitted answers, round history, and existing behavior for questions without a positive configured answer time limit.
- Show the active winner a server-deadline-based countdown; after server timeout release, all student clients return to the same-round available-to-buzz state.

## Capabilities

### New Capabilities

- `unanswered-buzz-lock-expiration`: Defines deadline creation, atomic unanswered-winner expiration, restart recovery, realtime release, and same-round rebuzz behavior.

### Modified Capabilities

None.

## Impact

- Java API buzz/submission lifecycle, scheduled background work, service startup/shutdown, Redis winner keys, and PostgreSQL buzz-record queries/updates.
- Realtime and question-state payloads, plus the student/teacher winner-state reducers and student winner countdown UI.
- Question/package time-limit validation and compatibility for nullable legacy limits.
- API integration and concurrency tests, web state tests, and deployment/validation documentation.
