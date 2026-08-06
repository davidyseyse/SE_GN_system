## 1. Persisted Winner Deadlines and Safe Locks

- [x] 1.1 Add the nullable winner deadline schema/index, model fields, and focused repository queries for active and overdue winners.
- [x] 1.2 Store a stable deadline and server-generated claim ID when a buzz wins, check overdue database winners before new arbitration, and replace unconditional Redis cleanup with compare-and-delete.

## 2. Atomic Submission and Expiration

- [x] 2.1 Add a shared transactional submission path that locks the active winner and atomically persists either an on-time legacy/package answer or a timeout outcome.
- [x] 2.2 Implement idempotent single-winner and batch timeout processing that records `TIMED_OUT`, preserves the open round, conditionally releases Redis, and publishes one timeout cancellation event.
- [x] 2.3 Wire synchronous startup recovery, daemon fixed-delay sweeping, error isolation, and scheduler shutdown into the API lifecycle.

## 3. Configuration and Client Reconciliation

- [x] 3.1 Validate explicitly supplied question/package answer limits as positive whole seconds while preserving existing null/non-positive stored legacy behavior.
- [x] 3.2 Add the teacher N-seconds authoring field with a 30-second default and send the configured value instead of the hard-coded constant.
- [x] 3.3 Handle timeout cancellation metadata on the student client, clear only the matching current-round winner/draft, and show an expiration notice while reopening buzzing.

## 4. Verification

- [x] 4.1 Add repository/service tests for stable deadlines, idempotent expiry, submit-versus-timeout arbitration, conditional Redis cleanup, lifecycle protection, and legacy no-timeout behavior.
- [x] 4.2 Add API integration/load coverage for same-round rebuzz, late submission rejection on both submission routes, on-time submission protection, concurrent boundary behavior, restart recovery, and a single timeout realtime event.
- [x] 4.3 Add web tests for configured N, current-round timeout reconciliation, stale timeout rejection, and the timeout notice.
- [x] 4.4 Run strict OpenSpec validation plus API/shared/web tests and builds, then review the final diff for regressions and unrelated changes.

## 5. Student Winner Countdown

- [x] 5.1 Expose the active winner's absolute answer deadline in buzz realtime/current-question contracts so refresh and reconnect preserve the original timer.
- [x] 5.2 Add a winner-only student countdown derived from the server deadline, clear it on authoritative winner-ending transitions, and return all students to the same-round buzz-ready state after timeout release.
- [x] 5.3 Add countdown/reconciliation tests and run focused API/web tests, builds, and strict OpenSpec validation.
- [x] 5.4 Position the winner countdown as a compact top-right question-card badge and verify responsive layout tests/build.
