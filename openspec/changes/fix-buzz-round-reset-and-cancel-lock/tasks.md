## 1. Data model and migration

- [x] 1.1 Define a persistent buzz-round model or equivalent round identifier for buzz questions.
- [x] 1.2 Associate buzz records and submissions with a round and backfill existing rows into legacy rounds.
- [x] 1.3 Replace question-wide winner and submission uniqueness constraints with round-scoped constraints.
- [x] 1.4 Add repository operations for current round lookup, active winner lookup, winner cancellation, and round-scoped submission checks.

## 2. Teacher lifecycle

- [x] 2.1 Start a new round when a buzz question transitions from `READY` to `BUZZ_OPEN`.
- [x] 2.2 End the current round and release its Redis lock when a teacher resets one question.
- [x] 2.3 Apply the same round-ending behavior to classroom-wide reset.
- [x] 2.4 Ensure reset leaves the question in `READY` until the teacher explicitly reopens it.

## 3. Buzz and cancellation API

- [x] 3.1 Scope Redis winner and sequence keys to the current round.
- [x] 3.2 Persist buzz attempts and enforce one active winner per round.
- [x] 3.3 Update cancellation to require the current active winner and an unsubmitted current round.
- [x] 3.4 Mark a cancelled winner inactive, preserve the audit record, keep the question in `BUZZ_OPEN`, and allow another winner.
- [x] 3.5 Compensate or release the Redis lock if persistence of a winning buzz fails.

## 4. Answer submission API

- [x] 4.1 Authorize buzz answers against the current round's active winner.
- [x] 4.2 Check duplicate submissions within the current round rather than across all question history.
- [x] 4.3 Reject cancellation after a current-round answer has been submitted.
- [x] 4.4 Preserve previous-round submissions for reporting and external synchronization.

## 5. Realtime events and student UI

- [x] 5.1 Include `roundId` in buzz-opened, buzz-result, buzz-cancelled, buzz-reset, and submission events.
- [x] 5.2 Track winner, draft, and submitted state by question and round in the student client.
- [x] 5.3 On student cancellation, clear the abandoned draft and restore the initial open-buzz interaction for eligible students.
- [x] 5.4 Show cancel lock only to the current winner before submission.
- [x] 5.5 Hide cancel lock immediately after a successful submission.
- [x] 5.6 On teacher reset, clear the ended round's local winner, draft, and submitted state and show the question as not open.
- [x] 5.7 Ignore stale realtime winner and cancellation events from previous rounds.

## 6. Verification

- [x] 6.1 Add a backend integration test covering submit, teacher reset, reopen, re-buzz, and second-round submission.
- [x] 6.2 Add a concurrent buzz test proving one active winner per round.
- [x] 6.3 Add a test covering winner cancellation before submission followed by a different student's successful buzz and submission.
- [x] 6.4 Add API tests proving non-winners and already-submitted winners cannot cancel.
- [x] 6.5 Add student UI tests for cancel visibility, post-submit hiding, reset cleanup, and stale-event handling.
- [x] 6.6 Run API, web, migration, and build verification.
