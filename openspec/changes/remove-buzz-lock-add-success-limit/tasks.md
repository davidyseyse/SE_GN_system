## 1. Persistence and policy

- [x] 1.1 Add idempotent schema, legacy backfill, and indexes for group success counts and one buzz-winner submission per round.
- [x] 1.2 Extend group and submission models/repositories with success-count and buzz-winner fields.
- [x] 1.3 Expose the effective `successfulBuzzLimit` policy through API responses.

## 2. Non-exclusive buzz registration

- [x] 2.1 Replace Redis winner locking with receipt sequencing only and remove winner-key lifecycle cleanup.
- [x] 2.2 Persist every under-limit buzz as an active recorded attempt without a deadline and return distinct recorded/limit outcomes.
- [x] 2.3 Make explicit cancellation operate on one student's attempt without clearing other attempts.

## 3. Fastest answer commit

- [x] 3.1 Implement a transactional fastest-active-attempt check, one-winner insert, and atomic group success-count increment.
- [x] 3.2 Update scalar and package submission controllers so skipped outcomes produce no score, event, submission, or Sheets side effect.
- [x] 3.3 Preserve accepted-winner scalar/package Sheets formatting, sync status, and retry behavior.

## 4. API and user interfaces

- [x] 4.1 Expose attempt recording, submission outcome, group success count, and effective limit through API/realtime/member payloads.
- [x] 4.2 Show group usage to teachers while keeping classroom creation controls usable.
- [x] 4.3 Let each student buzz and prepare an answer independently, keep other buzz buttons enabled, and render skipped/limit outcomes.

## 5. Verification and documentation

- [x] 5.1 Update API unit/integration/load coverage for concurrent open buzzes, fastest selection, group-limit enforcement, no deadlines, and no skipped Sheets writes.
- [x] 5.2 Update web tests for non-locking controls, independent attempt state, group-limit messaging, and current-round reconciliation.
- [x] 5.3 Update user cases, validation instructions, and deployment/rollback guidance to remove winner-lock semantics and document the new setting.
- [x] 5.4 Run API/web builds and tests plus strict OpenSpec validation.

## 6. Remove individual answer deadlines

- [x] 6.1 Stop creating, enforcing, and sweeping per-student buzz deadlines while preserving explicit cancellation and fastest-active ordering.
- [x] 6.2 Remove deadline/countdown fields and behavior from student API state and UI, including the teacher's unused answer-time input.
- [x] 6.3 Replace timeout-focused coverage and documentation with no-deadline persistence and explicit-cancellation behavior.
- [x] 6.4 Run API/web builds and tests plus strict OpenSpec validation after the deadline removal.

## 7. Per-group limit and dashboard layout

- [x] 7.1 Replace member-scoped success accounting with an idempotent group counter and atomic same-group concurrency enforcement.
- [x] 7.2 Expose group usage in buzz, submission, and teacher-member responses and update all user-facing labels/messages.
- [x] 7.3 Repair the classroom creation form with responsive field sizing and verify its source/build layout contract.
- [x] 7.4 Update group-limit tests and documentation, then run builds, API/web tests, and strict OpenSpec validation.

## 8. Global fixed group limit

- [x] 8.1 Add and backfill a global group counter, then enforce its fixed limit of one atomically across classrooms.
- [x] 8.2 Remove the teacher success-limit control and make every classroom report the fixed effective limit of one.
- [x] 8.3 Add cross-classroom coverage and update user, validation, and deployment documentation.
- [x] 8.4 Run API/web builds and tests plus strict OpenSpec validation.

## 9. Answer-first buzz and split login

- [x] 9.1 Render editable answers during `BUZZ_OPEN` and enable buzzing only when every answer is complete.
- [x] 9.2 Record the buzz and immediately submit prepared answers, cleaning up a recorded attempt when submission transport fails.
- [x] 9.3 Replace the combined login form with separate teacher and student routes, navigation, and role-guard redirects.
- [x] 9.4 Update tests and documentation, then run API/web builds and strict OpenSpec validation.

## 10. Audience navigation and classroom management

- [x] 10.1 Add authenticated owned-classroom list and owner-only classroom deletion API operations.
- [x] 10.2 Replace free-text classroom resume with a blank-first selector, creation auto-selection, and confirmed deletion.
- [x] 10.3 Remove cross-login links, show only the current audience login action, and unify create/resume button styling.
- [x] 10.4 Update automated tests and user documentation, then run API/web builds and strict OpenSpec validation.

## 11. Student classroom selection

- [x] 11.1 Add a read-only API that lists the existing classrooms available to students.
- [x] 11.2 Replace the student classroom-code input with a blank-first classroom selector and disable joining until all fields are valid.
- [x] 11.3 Update tests and documentation, then run API/web builds and strict OpenSpec validation.
