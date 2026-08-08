## Why

The teacher dashboard currently splits current-round answers on question cards from a separate historical submission-review panel. The duplicate presentation makes teachers leave the question context to edit an answer, exposes package-answer JSON in the review panel, and can show a group identity from an older round instead of the active answer.

Teachers need one authoritative, question-scoped place to inspect and correct the current answer and its group.

## What Changes

- Remove the teacher-facing `搶答檢討與學生答案修改` panel and its historical report query.
- Place answer editing directly inside each teacher question card.
- Enable answer editing after a current-round submission exists or after the teacher stops the question.
- Edit package answers as one labeled field per question item instead of exposing item-ID JSON.
- Store and edit the answer's group number as structured submission data rather than deriving editable state from a display-name string.
- Allow a teacher or assistant to create a group-attributed manual answer after a question is stopped when no student submitted.
- Treat an edited group as an answer-level snapshot; do not mutate the student's login identity or answers on other questions.
- Reconcile teacher and student question cards from the current-round answer API after changes and retain Google Sheets synchronization.
- Preserve the original buzz winner identity and original group on the teacher card, and always present the answer group separately as `挑戰：第N組`, even when it equals the original group, instead of concatenating another group onto the identity.
- Re-synchronize every bound Google Sheets cell after a teacher changes answer content or challenge group, expose synchronization failures, and retain retry capability.

## Capabilities

Google Sheets answer values change from `答案(組別)` to `答案_G組別`; for example, `ans` from group `1` is written as `ans_G1`.

### New Capabilities

- `teacher-question-card-answer-editing`: Defines question-card-local viewing, editing, and stopped-round manual entry of answers and group attribution.

### Modified Capabilities

- `classroom-submitted-answer-visibility`: Extends the current-answer projection with structured group attribution and readable package-answer presentation.
- `realtime-answer-submission`: Requires answer and group changes made by teachers to reconcile all connected classroom clients.

## Impact

- Teacher dashboard question-card state, controls, inline editor, and removal of the separate review panel.
- Current-round submission API contracts and teacher/assistant mutation authorization.
- Submission persistence and migration for an answer-level group number and manual-entry provenance.
- Package-answer parsing and serialization by question item identifier.
- Realtime invalidation and Google Sheets synchronization following answer or group edits.
- Teacher-card challenge-group presentation and visible Google Sheets synchronization status/retry handling.
- Frontend, API, repository, migration, and validation tests for current-round isolation, group correctness, package formatting, editing, and stopped-round manual entry.
