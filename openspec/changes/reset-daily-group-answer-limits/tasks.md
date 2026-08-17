## 1. API and persistence

- [x] 1.1 Add a repository operation that deletes only current-Taipei-date rows from `global_group_buzz_counts` and returns the affected-row count.
- [x] 1.2 Add the service/controller operation for resetting all current-day global group counters while preserving questions, submissions, scores, buzz records, and prior-day counters.
- [x] 1.3 Enforce teacher-or-assistant authorization and reject student or unauthenticated requests without mutation.
- [x] 1.4 Define the reset response with a success status and reset counter count, including a successful zero-count result.

## 2. Teacher dashboard

- [x] 2.1 Add the typed web API client function for the answer-limit reset operation.
- [x] 2.2 Add `重置答題限制` immediately beside `重置全部題目` using the existing batch-action styling conventions.
- [x] 2.3 Show the exact confirmation `確定要重置所有組別的答題限制嗎？` and make cancellation send no request.
- [x] 2.4 After success, reload member data so `今日成功搶答` reflects the authoritative current counts, and show distinct notices for reset and already-empty results.
- [x] 2.5 Keep the answer-limit reset independent from question reset and question lifecycle state.

## 3. Verification

- [x] 3.1 Add repository/API tests for current-date deletion, prior-date preservation, zero-row success, and unchanged historical submissions and question state.
- [x] 3.2 Add authorization tests covering teacher, assistant, student, and unauthenticated callers.
- [x] 3.3 Add concurrency coverage documenting reset ordering relative to a successful answer commit.
- [x] 3.4 Add web tests for button placement, exact confirmation text, cancellation, API invocation, member refresh, and success notices.
- [x] 3.5 Run the relevant API and web test suites and record manual validation of the displayed `0 / 1` usage.
