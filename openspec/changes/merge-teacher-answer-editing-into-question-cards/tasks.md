## 1. Submission group and manual-entry persistence

- [x] 1.1 Add nullable positive group attribution and manual-entry provenance to submission persistence with an idempotent migration.
- [x] 1.2 Backfill legacy group values only when the established display-name pattern can be parsed safely.
- [x] 1.3 Update submission models and repository reads/writes while preserving historical round data.

## 2. Current-answer and mutation APIs

- [x] 2.1 Extend current-answer responses with explicit group number and provenance without exposing round or timestamp metadata.
- [x] 2.2 Accept and validate answer content plus positive group number in teacher/assistant answer updates.
- [x] 2.3 Add stopped-round manual answer creation with classroom, question-state, round, role, and group uniqueness checks.
- [x] 2.4 Keep student identity/profile unchanged when an answer group is edited.

## 3. Package-answer transformation and integrations

- [x] 3.1 Parse current package answers into prompt-labeled fields and serialize edits back to item-ID-keyed JSON.
- [x] 3.2 Preserve legacy scalar-answer editing.
- [x] 3.3 Publish submission-change invalidation after committed content or group mutations.
- [x] 3.4 Synchronize updated answers as `答案_G組別` (for example `ans_G1`) to configured Google Sheets.

## 4. Teacher question-card interface

- [x] 4.1 Remove the `搶答檢討與學生答案修改` panel, historical report request, and related selection state from the teacher classroom page.
- [x] 4.2 Add an inline `修改答案` action and editor to each eligible current answer on its question card.
- [x] 4.3 Offer an empty inline manual-entry editor when a stopped current round has no answer.
- [x] 4.4 Render group separately from identity and render package values by item prompt instead of raw JSON.
- [x] 4.5 Preserve entered values and show actionable validation/server errors when saving fails.

## 5. Verification

- [x] 5.1 Add frontend tests for editor visibility across open, submitted, stopped-with-answer, and stopped-without-answer states.
- [x] 5.2 Add frontend tests for package rendering/edit serialization, group editing, cancel, save success, and failure preservation.
- [x] 5.3 Add API/repository tests for group validation, answer-level group isolation, manual-entry authorization, current-round filtering, and legacy backfill.
- [x] 5.4 Verify realtime reconciliation updates teacher and student cards and Google Sheets uses the saved answer/group.

## 6. Challenge attribution and reliable Sheets resynchronization

- [x] 6.1 Add a teacher-card attribution formatter that preserves the original buzz identity and always renders the answer group as `挑戰：第N組`.
- [x] 6.2 Cover equal groups as `1 / 第1組　挑戰：第1組`, differing groups, and teacher-entered answers without a winner; reject the ambiguous `原身分／新組別` presentation.
- [x] 6.3 Re-synchronize scalar and package target cells after teacher answer or challenge-group updates using the committed `答案_G組別` value.
- [x] 6.4 Record `SYNCED` after success and `RETRYABLE` plus an error reason after Sheets failure while keeping the answer mutation persisted.
- [x] 6.5 Return and display distinct answer-save and Sheets-sync outcomes, with a retry action that uses persisted data without another edit.
- [x] 6.6 Add integration tests for scalar/package update success, challenge-group suffix selection, Sheets failure visibility, and retry success.

## 7. Challenge-presence display and persisted Sheet binding

- [x] 7.1 Persist and expose whether a current answer has actually been challenged; initial student submissions are not challenged and teacher edits/manual entries are challenged.
- [x] 7.2 Hide the challenge label for an untouched student answer while retaining repeated same-group attribution after a real challenge.
- [x] 7.3 Resolve answer-update synchronization from the question package's persisted Sheet configuration and report missing credentials as retryable instead of not required.
