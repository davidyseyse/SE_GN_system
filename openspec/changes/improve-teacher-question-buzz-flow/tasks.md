## 1. Question lifecycle and legacy data

- [x] 1.1 Add an idempotent database migration that converts existing `DRAFT` questions to `READY` and exposes an affected-row count or equivalent verification.
- [x] 1.2 Change question creation to persist and return `READY` directly.
- [x] 1.3 Preserve safe read compatibility for legacy `DRAFT` values during rollout and document the targeted rollback condition.
- [x] 1.4 Update lifecycle validation and backend tests so new questions can open directly from `READY` without a separate transition.

## 2. Authoritative current-round teacher status

- [x] 2.1 Define the teacher-facing current-round status contract, including lifecycle state, `roundId`, derived buzz status, active winner identity/display name, submission status, and review status.
- [x] 2.2 Add repository queries that load current rounds, active winners, winner users, and current-round submissions for a classroom without per-question N+1 queries.
- [x] 2.3 Extend the authorized teacher question response or add an equivalent authoritative endpoint that returns the current-round status contract while keeping the student response appropriately scoped.
- [x] 2.4 Verify authoritative status for waiting, winner answering, submitted pending review, cancelled winner, stopped, reset, refresh, and server-reconnect cases.

## 3. Shared single-question state operations

- [x] 3.1 Introduce a transaction-capable service/repository boundary for one question's state update and buzz-round increment.
- [x] 3.2 Refactor single-question open so `READY → BUZZ_OPEN` and creation of exactly one new round are atomic and retry-safe.
- [x] 3.3 Preserve complete-buzz-and-review so open questions become `LOCKED`, retain the current round and records, and can be selected for review without invoking scoring or submission review.
- [x] 3.4 Refactor single-question reset to release current Redis interaction keys, transition affected states to `READY`, retain persistent history, and publish success only after required operations succeed.
- [x] 3.5 Add concurrency tests for buzz or submission requests racing with stop and reset boundaries.

## 4. Classroom batch controls

- [x] 4.1 Add an authorized classroom batch-control API accepting `OPEN_ALL`, `STOP_ALL`, and `RESET_ALL`.
- [x] 4.2 Implement `OPEN_ALL` for eligible `READY` buzz questions using the shared atomic open operation and ensure retries do not create extra rounds.
- [x] 4.3 Implement `STOP_ALL` for `BUZZ_OPEN` and `ANSWER_OPEN` questions, changing them to `LOCKED` without releasing winners, clearing client interaction state, or advancing rounds.
- [x] 4.4 Implement `RESET_ALL` for active or locked buzz questions using the shared reset operation while skipping finalized states.
- [x] 4.5 Return per-question `CHANGED`, `UNCHANGED`, `SKIPPED`, or `FAILED` outcomes with previous/resulting state, round identifier, and reason.
- [x] 4.6 Publish the correct per-question state or reset event only for successfully changed items.
- [x] 4.7 Add authorization, mixed-state, partial-failure, idempotency, and multi-question round-isolation integration tests for all three batch actions.

## 5. Realtime teacher synchronization

- [x] 5.1 Confirm winner, cancellation, submission, stop, and reset events include the required `questionId`, `roundId`, user/display, submission, and review fields.
- [x] 5.2 Add a teacher-side round-scoped event reducer that updates only the affected current round and ignores old-round events.
- [x] 5.3 Reconcile teacher state from the authoritative response on initial load, refresh, reconnect, and relevant realtime events.
- [x] 5.4 Add frontend tests covering out-of-order events, missing event history, winner cancellation, submission, stop, and reset.

## 6. Teacher interface

- [x] 6.1 Remove the “設為未開放” button and remove its normal-flow `DRAFT → READY` client transition path.
- [x] 6.2 Retain “完成搶答並檢討” and make its valid states, stop behavior, question selection, and answer-panel result clear.
- [x] 6.3 Retain “重置本題” with reset semantics distinct from stopping and show it only when applicable.
- [x] 6.4 Render accessible text/icon/color treatments for not open, waiting for buzz, winner answering, submitted pending review, and stopped states, including the winner display name where applicable.
- [x] 6.5 Add “一鍵開放全部搶答”, “一鍵停止全部搶答”, and “一鍵重置全部題目” controls with valid-state handling and reset confirmation.
- [x] 6.6 Display batch summaries that distinguish changed, unchanged, skipped, and failed questions instead of showing unconditional success.

## 7. End-to-end validation and rollout

- [x] 7.1 Update existing validation and load scripts that currently perform `DRAFT → READY` after question creation.
- [x] 7.2 Run backend build, integration, concurrency, and load validation for single-question and batch flows.
- [x] 7.3 Run frontend build and tests, then manually verify teacher/student behavior across open, win, cancel, submit, stop, review selection, reset, reopen, and reconnect.
- [x] 7.4 Verify the legacy migration leaves no unintended `DRAFT` questions and that historical buzz/submission data remains queryable after reset and reopen.
- [x] 7.5 Document deployment order, monitoring signals for batch failures and remaining drafts, and the scoped rollback procedure.

