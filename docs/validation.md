# Validation Commands

## Automated Validation

Google Sheet 組別統計人工驗證：在不同工作表放入 `答案_G2`、`答案_G2`、`答案_G5`，教師按「統計組別」後應只列出「第 2 組」與「第 5 組」各一次，不顯示成功次數、加給分或總得分。其他結尾應忽略，學生權杖呼叫 `POST /api/sheet-configs/statistics` 應回傳 403。
另以單格 `fdsfs_G1/ asda_G2` 驗證畫面列出第 1、2 組；單格 `first_G1/ retry_G1` 只列出第 1 組一次。

- `npm.cmd run test --workspace @classroom/api`

This runs:

- Java smoke verification
- referenced Sheet configuration deletion, dependent-package cascade count, and post-delete list checks
- authorization rejection checks
- teacher-owned classroom listing, owner-only deletion, and post-delete list checks
- public minimal classroom listing and student blank-first classroom selection
- question state transition checks
- new-question `READY` state and legacy `DRAFT` migration checks
- duplicate and skipped submission protection checks
- non-exclusive buzz recording and one committed winner per round
- buzz attempts remain active without an individual deadline until submission, cancellation, or round lifecycle change
- atomic per-group successful-buzz limit checks, including different students in the same group
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

Use two separate browser sessions: sign the teacher in at `/teacher/login` and open `/student/login`, then choose the same classroom from the student's dropdown. Confirm the student cannot join before entering a student ID, positive group number, and selecting a classroom. Also confirm neither page contains a role selector or a link to the other login page, and the header shows only the matching audience login action.

Before joining, confirm the teacher dashboard classroom selector starts blank, lists only classrooms created by that teacher, and enables resume/delete after selection. Create a classroom and confirm it is selected automatically. Delete a disposable classroom and confirm the warning appears and the option disappears.

1. Create two buzz questions. Confirm both are immediately `READY`, then open question 1.
   - The student lobby displays question 1.
   - Question 1 shows editable answer fields while its buzz button remains disabled until every answer is complete.
2. Without closing question 1, open question 2.
   - The student lobby displays question 1 and question 2 as separate cards.
   - Both questions independently show their current state.
3. Complete question 1, leave question 2 partially filled, then click “搶答並送出答案” on question 1.
   - Question 1 records the attempt and immediately submits its prepared answers with no personal countdown.
   - Question 2 keeps its draft and its buzz availability unchanged.
4. Confirm question 1 reports submission success first, then reports success or failure only after the teacher completes review.
5. Retry the same pending submission (for example after simulating a lost response); confirm it still reports submission success and only one submission row exists.
6. Reset and reopen the question; confirm the previous round's success/failure notice is absent before the new submission.
   - Submission and notice state change only on question 1.
   - Question 2 remains independently interactive.
7. Close question 2 from the teacher dashboard.
   - Question 2 is removed from the student lobby.
   - Question 1 remains visible and retains its interaction state.
8. Repeat the original single-question flow.
   - Attempt recording, unlimited answer time, fastest-answer selection, personal cancellation, and skipped outcomes behave consistently.

## No Individual Answer Deadline Verification

1. Open a buzz question, let a student prepare an answer, and wait longer than any legacy `answerTimeLimitSeconds` value before buzzing.
2. Confirm the student still sees the prepared answer with no countdown or timeout notice.
3. Click “搶答並送出答案” and confirm the answer shows as submitted but no winner or Sheet write appears yet; then have the teacher click “完成搶答並檢討” and confirm the fastest eligible attempt is selected and synchronized.
4. Submit in the order group 1, group 1 again, group 2, then group 3; confirm the teacher card shows only the first group 1 answer and the first group 2 answer.
4. Repeat with two students, explicitly cancel the lowest receipt-order attempt, and confirm the next active attempt becomes eligible.

## Teacher Batch-Control Verification

1. Create at least two buzz questions and use “開放全部搶答”.
   - Every `READY` buzz question opens with its own new round.
   - Repeating the action does not advance already-open rounds.
2. Let multiple students submit answers for multiple open questions, then use “完成搶答並檢討”. Confirm all active buzz questions stop, finalize sequentially, and write each winning answer to its configured Excel/Google Sheet cell.
   - Active questions become `LOCKED`.
   - The finalized fastest eligible answer and review state remain visible.
   - New buzz and submission requests are rejected.
3. Use “重置全部題目” and confirm the warning.
   - Active or locked buzz questions become `READY`.
   - All answer submissions and buzz history for eligible buzz questions are deleted, including residual records on questions already in `READY`.
   - The warning states that the deletion cannot be undone, and the teacher answer cards clear after success.
   - Opening again creates new rounds; daily group limits and values already written to Google Sheets remain unchanged.

## Question Package Verification

### Marker-generated packages

1. Share two Google Sheets with the service account, place `[[Q1-1]]`, `[[Q1-2]]`, and `[[Q2]]` in a source worksheet, and leave the same coordinates blank in an answer worksheet.
2. Select the independent source and answer spreadsheet/worksheet pairs and confirm that nothing happens until `自動偵測生成題目` is pressed.
3. Confirm the preview groups Q1, creates standalone Q2, ignores images and surrounding text, and maps every item to the same A1 coordinate.
4. Edit preview descriptions, exclude one package, confirm, and verify only the selection is created in READY state.
5. Repeat with the exact same spreadsheet and worksheet; verify markers are accepted as destination contents and provenance remains after the reviewed winner overwrites a marker.
6. Verify duplicate markers, existing source bindings, and existing answer bindings block confirmation; verify occupied destination values/formulas and numbering gaps do not block, and only the teacher-finalized winning answer overwrites the destination.
7. Edit a READY package using `編輯`, then open it and verify further edits are rejected.
8. Inspect student list and realtime responses and verify they contain no sheet configuration, worksheet, marker, source cell, or target cell fields.

1. Create a Google Sheet configuration, enter a package title, and confirm the form initially contains only non-removable subquestion 1.
2. Add subquestions 2 and 3, remove subquestion 2, and confirm the remaining subquestions are numbered 1 and 2 without moving their entered values.
3. Confirm blank fields, malformed or multi-cell ranges, and duplicate normalized cells keep creation disabled and are rejected by the API.
4. Create a valid multi-item package and confirm its title, ordered prompts, and bindings survive reload as one package.
5. Open the package and confirm every student can edit all item answers before buzzing, while incomplete answers keep the buzz button disabled.
6. Complete all answers in multiple sessions and click “搶答並送出答案” concurrently; confirm every complete answer is stored as pending and no Sheet cell changes. Click “完成搶答並檢討”; confirm only the lowest eligible receipt order writes each bound cell and all other submissions display failure.
7. Reset and reopen the package. Confirm the old winner/drafts clear only for that package and the next round accepts a new winner and submission.
8. Confirm the student page contains no realtime event-history panel while package state and winner changes continue updating without reload.

## First-Win Priority Verification

1. Create two classrooms; confirm the teacher UI has no success-limit field, then join students from groups 1, 2, and 3.
2. Submit in order 1, 2, 3 for three consecutively finalized questions; confirm the winners are groups 1, 2, and 3 and the member rows display `1 次`.
3. Submit in order 1, 2, 3 for a fourth question; confirm group 1 wins through fastest fallback, its count displays `2 次`, and the winning answer is written to Sheets.
4. Repeat through six questions and confirm winners `1, 2, 3, 1, 1, 1`, final counts `4, 1, 1`, and six winner answers written to Sheets.
5. Finalize two questions concurrently with identical candidates; confirm serialized decisions grant the two initial first wins to different groups.
6. Insert or retain a group count dated before the current Asia/Taipei date; confirm the teacher view reports `0 次` for today and the group receives first-win priority again.
7. Reset today's success counts and confirm historical submissions and Sheets values remain while all groups regain first-win priority.

## Deployment and Rollback for Question Packages

1. Back up PostgreSQL and run `infra/postgres/verify-question-package-migration.sql` before deployment to inventory invalid legacy bindings.
2. Apply `infra/postgres/init.sql`; it is idempotent and backfills one item per existing question while retaining aggregate and history IDs.
3. Run the verification SQL again. Correct packages listed with missing sheet configuration or target cells before expecting Google Sheets synchronization.
4. Deploy the additive API before the web client. Monitor package creation errors and submissions whose `sheet_sync_status` is `RETRYABLE`.
5. Retry a failed sheet synchronization by overwriting the same configured cells with the persisted item-ID-keyed answers; the operation is idempotent.
6. For rollback, restore the previous web client and use the legacy one-item adapter. Keep additive columns and item rows; do not drop or reverse migrated history.
