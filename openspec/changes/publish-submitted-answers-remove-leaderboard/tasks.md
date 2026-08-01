## 1. Backend answer read model and authorization

- [x] 1.1 Add a current-round submission projection that joins submission identity with the existing student display identity and filters by the server-derived current question round.
- [x] 1.2 Add a classroom-member answer query endpoint that verifies authentication, classroom membership, and question ownership before returning answers.
- [x] 1.3 Exclude submission timestamps and round identifiers from the classroom-facing response while retaining them in persistence and administrative reporting.
- [x] 1.4 Add authorization tests for teacher, assistant, student member, authenticated non-member, and cross-classroom question access.

## 2. Realtime answer propagation

- [x] 2.1 Define submission create, update, delete, and review invalidation events with question and submission identifiers.
- [x] 2.2 Publish each event only after its corresponding persistence operation succeeds.
- [x] 2.3 Update realtime shared contracts and formatting while avoiding answer text in the transport event.
- [x] 2.4 Add tests covering successful delivery, missed-event recovery, and current-round reconciliation after reset.

## 3. Teacher answer presentation

- [x] 3.1 Automatically load current answers for visible teacher question cards on entry, refresh, reconnect, and resync.
- [x] 3.2 Reconcile the affected question after submission-change events without requiring `selectedQuestionId` or the complete-and-review action.
- [x] 3.3 Render student display identity and answer inline, omit timestamp and round, and show only `✓ 已作答` as the visible submitted status.
- [x] 3.4 Preserve the existing review/edit/delete workflow and verify that edits and deletions update all connected clients.

## 4. Student answer presentation and submission locking

- [x] 4.1 Automatically load and display all current classroom answers on the corresponding student question cards.
- [x] 4.2 Restore the submitting student's answer and submitted state after refresh or reconnect.
- [x] 4.3 Add a question-and-round-scoped in-flight flag that immediately disables submit on first click without changing the button label.
- [x] 4.4 Keep the button disabled after success; on failure preserve the draft, clear the in-flight flag, re-enable submission, and show the error.
- [x] 4.5 Keep server duplicate protection and test rapid clicks, request failure/retry, reload, reset, and reopened-round behavior.

## 5. Remove leaderboard while retaining scoring

- [x] 5.1 Remove teacher and student leaderboard panels, client state/queries, visibility selector, related text, and shared frontend types.
- [x] 5.2 Remove the leaderboard route, controller handler, ranking service, snapshot repository/model wiring, and leaderboard-specific realtime event.
- [x] 5.3 Preserve score policies, score writes and reversals, score detail, reporting, and exports; add regression tests for each retained path.
- [x] 5.4 Add a staged, idempotent migration for obsolete leaderboard snapshots/indexes and leaderboard visibility configuration with a documented rollback path.
- [x] 5.5 Update deployment, validation, and user-facing documentation to remove leaderboard checks and describe automatic answer visibility.

## 6. Batch button labels

- [x] 6.1 Rename the visible buttons to `開放全部搶答`, `停止全部搶答`, and `重置全部題目`.
- [x] 6.2 Verify the buttons still invoke `OPEN_ALL`, `STOP_ALL`, and `RESET_ALL` with unchanged confirmation and outcome behavior.

## 7. End-to-end validation

- [x] 7.1 Verify one student's successful submission appears automatically for the teacher, submitting student, and another student without manual refresh.
- [x] 7.2 Verify no submission timestamp, round label, or `待檢討` text is displayed.
- [x] 7.3 Verify refresh, reconnect, teacher edit/delete, and question reset/reopen produce authoritative current answers.
- [x] 7.4 Verify Google Sheets synchronization, buzz locking, answer persistence, review, scoring, score detail, and exports still work after leaderboard removal.


