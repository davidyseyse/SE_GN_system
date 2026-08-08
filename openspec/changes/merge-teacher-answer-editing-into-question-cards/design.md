## Context

Teacher question cards already load the authoritative current-round answer projection through `/api/submissions`. A second panel loads `/api/reports/submissions`, which returns historical submissions and renders raw stored answers. Package answers are stored as JSON keyed by question-item UUID. Student group is currently embedded in `users.display_name`, so it is unsuitable as editable, answer-scoped data.

## Goals / Non-Goals

**Goals:**

- Make each question card the only teacher classroom surface for current answer review and correction.
- Display and edit package answers using item prompts.
- Persist a correct, independently editable group snapshot on each answer.
- Support teacher manual entry after a stopped round has no submission.
- Preserve round isolation, realtime reconciliation, authorization, and Sheets synchronization.

**Non-goals:**

- Changing a student's login profile or default group.
- Removing administrative historical submission reports or stored historical rounds.
- Adding student-side answer editing or resubmission.
- Changing scoring or review policy.

## Decisions

### Use the current-round answer projection in question cards

The teacher UI will stop querying the historical report endpoint for classroom editing. Each card renders only answers returned by the membership-authorized current-round endpoint. Administrative reporting remains available outside this classroom editing flow.

### Store group attribution on the submission

Add a nullable positive `group_number` snapshot to submissions and expose it in current-answer views. Student submission copies the authenticated student's current group into this field. Teacher edits update only this snapshot. Existing rows are backfilled by safely parsing the established display-name pattern where possible and remain nullable otherwise.

### Represent manual entries explicitly

After a question is stopped, a teacher or assistant may create a manual current-round answer with a required group number. The record retains actor/provenance metadata while its classroom presentation is attributed to the entered group. Manual creation is rejected while the question is open and when it would violate the chosen current-round uniqueness rule for that group.

### Edit package answers structurally

The client parses item-keyed JSON and renders one input per current question item. Saving serializes the same item-ID-keyed object. Legacy scalar answers continue to use a single text field. Unknown item keys are not presented and are preserved only if required for backward compatibility.

### Reconcile after every mutation

Create and update operations publish the existing submission-change invalidation with question and submission identifiers. Connected clients refetch the affected current-round answer projection. Google Sheets receives the updated formatted answer and group.

### Separate original buzz identity from challenge group

The original buzz winner identity remains the persisted student display name, including its original group, such as `1 / 第1組`. The editable submission `group_number` is the challenge group. Whenever an answer group is present, the teacher card displays it separately without suppressing a repeated group:

- Equal groups render `1 / 第1組　挑戰：第1組`.
- Different groups render `1 / 第1組　挑戰：第6組`.
- A teacher-entered answer without an original student winner renders `挑戰：第6組`.

The UI must not render the ambiguous concatenation `1 / 第1組／第6組`. Google Sheets continues to use the challenge group, so the updated value uses `_G6` in this example.

### Make post-edit Sheets synchronization observable and retryable

After answer content or challenge group is updated, a scalar question updates its configured `targetCell`; a package question updates every item `targetCell`. Values use the committed answer and challenge group in `<answer>_G<groupNumber>` format.

Successful synchronization records `SYNCED`. A Sheets configuration, authorization, network, or API failure records `RETRYABLE` with the error reason. The answer mutation remains persisted, and the teacher response distinguishes full success from `答案已儲存，但 Google Sheets 同步失敗`. Retrying synchronization reuses the persisted answer and challenge group and does not require another answer edit.

## Risks / Trade-offs

Google Sheets synchronization formats every scalar or package-item value as `<answer>_G<groupNumber>` (for example `ans_G1`). Without a valid legacy group snapshot, it keeps the answer unchanged rather than emitting malformed group data.

- Backfilling a group from display names can be incomplete. Nullable legacy values and explicit teacher correction avoid inventing a group.
- Manual answers lack a natural student owner. Explicit provenance prevents them from being mistaken for a student-authored record internally.
- Removing the review panel also removes its delete shortcut. Deletion is intentionally excluded from the primary question-card workflow unless retained product behavior requires it later.
- Concurrent teacher edits can overwrite one another. The first implementation may use last-write-wins; a later revision can add version checks if classroom usage demonstrates a need.
- Persisting the answer before an external Sheets call can produce temporary divergence; explicit `RETRYABLE` state and an idempotent retry action make that divergence visible and recoverable.

## Migration Plan

1. Add nullable submission group and manual-entry provenance fields without changing existing reads.
2. Backfill groups that match the known student display-name format.
3. Extend current-answer reads and teacher mutations.
4. Deploy the inline editor and remove the teacher review panel query/UI.
5. Verify current and historical answers remain round-isolated and Sheets values are correct.

Rollback keeps the additive columns and restores the former teacher panel; no historical submission needs to be deleted.
