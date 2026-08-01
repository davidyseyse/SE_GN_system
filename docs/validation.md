# Validation Commands

## Automated Validation

- `npm.cmd run test --workspace @classroom/api`

This runs:

- Java smoke verification
- authorization rejection checks
- question state transition checks
- new-question `READY` state and legacy `DRAFT` migration checks
- duplicate submission protection checks
- buzz winner uniqueness checks
- teacher current-round winner/submission recovery checks
- authorized batch open, stop, reset, mixed-state and retry checks
- current-answer visibility, scoring detail, and export verification
- audit log verification

## Load Verification

- `npm.cmd run test:load --workspace @classroom/api`

This runs:

- 100 concurrent student join requests
- 50 concurrent buzz attempts
- WebSocket reconnect and resync verification

## Frontend Build Verification

- `npm.cmd run build --workspace @classroom/shared`
- `npm.cmd run build --workspace @classroom/web`

## Student Multi-Question Verification

Use two separate browser sessions: one signed in as a teacher and one signed in as a student in the same classroom.

1. Create two buzz questions. Confirm both are immediately `READY`, then open question 1.
   - The student lobby displays question 1.
   - Question 1 has an enabled buzz button.
2. Without closing question 1, open question 2.
   - The student lobby displays question 1 and question 2 as separate cards.
   - Both questions independently show their current state.
3. Enter a draft answer for question 2, then buzz on question 1.
   - Question 1 alone reflects the buzz winner and lock state.
   - Question 2 keeps its draft and its buzz availability unchanged.
4. As the winner, submit or cancel the lock for question 1.
   - Submission and notice state change only on question 1.
   - Question 2 remains independently interactive.
5. Close question 2 from the teacher dashboard.
   - Question 2 is removed from the student lobby.
   - Question 1 remains visible and retains its interaction state.
6. Repeat the original single-question flow.
   - Winner display, answer submission, cancel lock, and disabled states behave as before.

## Teacher Batch-Control Verification

1. Create at least two buzz questions and use “開放全部搶答”.
   - Every `READY` buzz question opens with its own new round.
   - Repeating the action does not advance already-open rounds.
2. Let one student win and submit, then use “停止全部搶答”.
   - Active questions become `LOCKED`.
   - Current winners, answers and review state remain visible.
   - New buzz and submission requests are rejected.
3. Use “重置全部題目” and confirm the warning.
   - Active or locked buzz questions become `READY`.
   - Finalized questions are skipped and reported.
   - Opening again creates new rounds while historical records remain queryable.

## Question Package Verification

1. Create a Google Sheet configuration, enter a package title, and confirm the form initially contains only non-removable subquestion 1.
2. Add subquestions 2 and 3, remove subquestion 2, and confirm the remaining subquestions are numbered 1 and 2 without moving their entered values.
3. Confirm blank fields, malformed or multi-cell ranges, and duplicate normalized cells keep creation disabled and are rejected by the API.
4. Create a valid multi-item package and confirm its title, ordered prompts, and bindings survive reload as one package.
5. Open the package and submit simultaneous buzz requests; confirm exactly one package winner and no item-level buzz endpoint succeeds.
6. As the winner, fill every subquestion and submit once. Confirm each answer is written to its bound cell and the submission exposes `SYNCED` or retryable sync state.
7. Reset and reopen the package. Confirm the old winner/drafts clear only for that package and the next round accepts a new winner and submission.
8. Confirm the student page contains no realtime event-history panel while package state and winner changes continue updating without reload.

## Deployment and Rollback for Question Packages

1. Back up PostgreSQL and run `infra/postgres/verify-question-package-migration.sql` before deployment to inventory invalid legacy bindings.
2. Apply `infra/postgres/init.sql`; it is idempotent and backfills one item per existing question while retaining aggregate and history IDs.
3. Run the verification SQL again. Correct packages listed with missing sheet configuration or target cells before expecting Google Sheets synchronization.
4. Deploy the additive API before the web client. Monitor package creation errors and submissions whose `sheet_sync_status` is `RETRYABLE`.
5. Retry a failed sheet synchronization by overwriting the same configured cells with the persisted item-ID-keyed answers; the operation is idempotent.
6. For rollback, restore the previous web client and use the legacy one-item adapter. Keep additive columns and item rows; do not drop or reverse migrated history.
