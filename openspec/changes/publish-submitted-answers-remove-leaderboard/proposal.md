## Why

Submitted answers are persisted and synchronized to Google Sheets, but the classroom interface does not make them visible at the moment of submission. The teacher question card only shows the buzz winner and a pending-review status, while students lose the submitted text from local state and cannot recover their own or classmates' answers after refresh. The leaderboard occupies both teacher and student interfaces even though ranking display is no longer wanted.

The classroom should instead make a successfully submitted answer immediately visible to every connected classroom member, recover the same view after refresh or reconnect, and keep scoring independent from the removed leaderboard presentation.

## What Changes

- Add an authenticated classroom-member answer read model/API that returns submitted answers for a question's current internal round.
- Automatically load answers on classroom entry, refresh, reconnect, and relevant realtime changes; no user-facing “load answers” button is added.
- Notify connected clients when an answer is created, edited, deleted, or reviewed, then reconcile the affected question from the authoritative API.
- Display each submitted answer inline on the corresponding teacher and student question card, including the submitting student's existing display identity and the answer text.
- Do not display submission timestamps or internal round identifiers.
- Replace the visible pending-review wording with `✓ 已作答`; review state remains available internally.
- Disable the submitting student's submit button immediately after the first click without changing its label. Keep it disabled after success, and re-enable it only if submission fails.
- Preserve server-side duplicate-submission protection and use the internal question round to keep reset/reopened answers separate.
- Remove leaderboard panels, visibility controls, client queries, realtime ranking notifications, leaderboard API/service/snapshot dependencies, and obsolete documentation and validation.
- Preserve score records, scoring, score detail/reporting, answer persistence, Google Sheets synchronization, buzz locking, and question reset behavior.
- Rename the teacher batch buttons to `開放全部搶答`, `停止全部搶答`, and `重置全部題目` without changing the underlying batch commands.

## Capabilities

### New Capabilities

- `classroom-submitted-answer-visibility`: Defines immediate, automatic, classroom-member visibility and recovery of submitted answers.

### Modified Capabilities

- `realtime-answer-submission`: Adds client-side in-flight submission locking and authoritative realtime answer reconciliation while retaining one submission per active round.
- `scoring-and-leaderboard`: Removes leaderboard presentation and snapshot behavior while preserving scoring and score reporting.
- `classroom-buzz-batch-control`: Changes only the visible teacher labels for the existing batch operations.

## Impact

- React teacher and student question-card state, answer rendering, initial/reconnect data loading, and submit-button state.
- REST authorization and a classroom-member answer query endpoint.
- Submission create/update/delete/review realtime event contracts.
- Submission repository projections that include current internal round filtering and student display identity.
- Removal of leaderboard client state, API calls, UI, shared types/text, backend route/service/repository/model, snapshot persistence, and visibility configuration.
- Database migration planning for obsolete leaderboard configuration and snapshot storage.
- Tests for membership isolation, refresh/reconnect recovery, immediate multi-client visibility, submission failure recovery, round reset separation, and preserved scoring.

