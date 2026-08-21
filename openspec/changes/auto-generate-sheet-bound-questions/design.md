## Context

The current system stores a question package in `questions`, ordered subquestions in `question_items`, one package-level `sheet_config_id`, and one answer `target_cell` per item. Teachers manually enter package/item descriptions and target cells. `GoogleSheetsSyncService` can read spreadsheet grid values and write one or many answer cells, but it does not expose worksheet discovery or marker scanning. The legacy `PUT /api/questions` updates the aggregate prompt but does not update package items atomically.

The new workflow treats a marker cell as authoring metadata. It does not infer meaning from nearby text or images. The selected destination uses the same worksheet-relative A1 coordinate as the source marker, but may belong to another spreadsheet and worksheet.

## Goals / Non-Goals

**Goals:**

- Make marker detection deterministic and explicitly teacher-triggered.
- Support independent source and answer spreadsheet/worksheet selection.
- Preview and edit generated descriptions before persistence.
- Prevent duplicate imports while allowing teachers to intentionally replace existing answer-cell contents.
- Preserve source provenance after marker cells are overwritten.
- Allow safe editing of existing package and item descriptions.
- Prevent students from receiving spreadsheet provenance or bindings.

**Non-Goals:**

- OCR or interpretation of images, surrounding labels, formulas, formatting, or layout.
- Importing spreadsheet images into classroom questions.
- Mapping a source marker to a different A1 coordinate.
- Adding, deleting, or reordering items through the description editor.
- Editing an active or historical package in ways that change answer meaning.
- Direct Google Forms integration.

## Decisions

### 1. Use an exact, anchored marker grammar

The scanner trims cell values, compares case-insensitively, and normalizes accepted markers to uppercase. It accepts only:

```regex
^\[\[Q([1-9][0-9]*)(?:-([1-9][0-9]*))?\]\]$
```

`[[Q1-1]]` and `[[Q1-2]]` become two items in package `Q1`. `[[Q2]]` becomes a one-item package whose title and item description are both `Q2`. Default descriptions omit brackets. Surrounding cells, images, image formulas, floating images, formatting, comments, and notes do not affect detection.

Duplicate normalized markers are blocking errors. Gaps such as `Q1-1` and `Q1-3` are warnings rather than errors; item order follows the numeric suffix and then stable sheet position. Mixing `[[Q1]]` with `[[Q1-m]]` is rejected because it creates ambiguous one-item and multi-item definitions for the same package code.

### 2. Separate scan from confirmation

The teacher selects:

- source sheet configuration;
- source worksheet;
- answer sheet configuration; and
- answer worksheet.

There is no mapping control. Pressing `自動偵測生成題目` calls a scan endpoint and returns a preview. The preview contains editable package and item descriptions, source and answer locations, warnings, errors, and whether each package is selected for creation. Nothing is persisted until the teacher confirms.

The confirmation request carries a server-issued scan token plus the selected and edited preview entries. The server reloads or verifies the scan snapshot before creation so the client cannot invent a source marker or bind an arbitrary coordinate.

### 3. Always map to the same A1 coordinate

For marker `sourceSpreadsheet/sourceWorksheet!B2`, the answer target is `answerSpreadsheet/answerWorksheet!B2`. Spreadsheet IDs and worksheet names may differ. Identical source and answer spreadsheet/worksheet selections are permitted, in which case the source and target are the same cell.

The answer worksheet must already exist and the service account must be able to write it. Existing values and formulas in the mapped answer cells do not block preview or confirmation. Question creation stores only the binding and does not immediately mutate Google Sheets; the answer selected during teacher review later overwrites the existing destination through the normal idempotent values update. An answer coordinate already bound to another active generated item remains a blocking conflict.

### 4. Persist provenance separately from answer binding

Keep the package's existing `sheet_config_id` as the answer spreadsheet configuration and each item's `target_cell` as the answer A1 coordinate. Add package-level source configuration and worksheet information plus answer worksheet information, and item-level source A1 cell and normalized source marker.

Uniqueness is enforced for active generated items on:

- `(classroom_id, source_sheet_config_id, source_worksheet, source_cell)`; and
- `(classroom_id, answer_sheet_config_id, answer_worksheet, target_cell)`.

This prevents repeated scans from creating the same source twice or assigning two active items to the same destination. Provenance remains after a same-cell marker is overwritten by a student answer. Archived bindings remain auditable; reusing them requires an explicit future restore/reimport workflow rather than silent duplication.

### 5. Create selected generated packages transactionally

Confirmation validates the complete selection before opening a database transaction. All selected packages, items, provenance, and bindings are inserted in one transaction. A validation or persistence failure produces no partial generated batch. Each created package starts in `READY`, matching manual package creation.

The response returns created packages and teacher-only provenance. Realtime and student list payloads contain titles and item descriptions but omit all sheet configuration IDs, spreadsheet names/IDs, worksheet names, source cells, markers, and target cells.

### 6. Provide a package-specific description update contract

Add a package update endpoint accepting package ID, title, and the complete authoritative set of `{itemId, prompt}` entries. The service locks the package, requires `READY`, verifies exact item membership with no missing, duplicate, or foreign IDs, and updates the title and item prompts in one transaction.

The operation cannot change package code metadata, item IDs, item order, source provenance, sheet selections, or target cells. The teacher card labels the action `編輯`. Active or previously advanced packages must be reset or recreated rather than having historical question meaning changed.

### 7. Keep provenance teacher-only at every boundary

Teacher package-management responses may include provenance and answer-binding details. Student endpoints and realtime payloads use explicit student serializers rather than reusing teacher serializers with client-side hiding. This prevents source or answer locations from being discoverable through browser inspection.

### 8. Delete a Sheet configuration and its dependent packages transactionally

A Sheet configuration may be referenced by manual package answer bindings or by generated-package source and answer provenance. Deleting only the configuration is rejected by PostgreSQL foreign keys and leaves the teacher without actionable feedback. The teacher interface therefore requires an explicit destructive confirmation, and the repository deletes every dependent package before deleting the configuration in one transaction. Package deletion uses the existing cascade relationships for items, submissions, buzz history, scores, and generated bindings.

Packages are considered dependent when their package-level answer configuration matches the deleted configuration or any generated item references it as its source or answer configuration. If any delete fails, the transaction rolls back so neither the configuration nor a partial subset of packages disappears.

### 9. Award at most one point per group per answer cell

Teacher Sheet statistics treat each populated cell as one question score boundary. The parser splits the formatted cell value on ASCII or full-width slash separators, extracts a positive group suffix from each segment using `_G<number>`, and deduplicates group numbers within that cell before incrementing totals. Thus `fdsfs_G1/ asda_G2` awards one point to groups 1 and 2, while `first_G1/ retry_G1` awards only one point to group 1. The same group may receive another point from a different cell.

The API retains these counts for compatibility, but the teacher interface treats the result as group membership only. It lists each distinct group number once and deliberately omits per-group points, successful-answer counts, summary cards, and total scores.

### 10. Separate buzz submission receipt from teacher finalization

Submitting a buzz answer creates a pending submission associated with the student's recorded attempt and round. It does not select a winner, increment the global group counter, award a buzz bonus, or call Google Sheets. The response uses a pending outcome so the student can confirm receipt without learning the result early.

Pending submission creation is idempotent on the existing question/user/round/attempt uniqueness boundary. If a client retries after the first response is lost, the service confirms the existing `PENDING_REVIEW` row and returns the same successful pending outcome rather than converting a transport retry into a visible buzz failure. Student notices are keyed by both question ID and round ID so reviewed outcomes cannot leak into a newly opened round.

Before review, client-side rejection and transport paths use submission-retry wording only. They never translate an API outcome into a buzz failure. The student interface reserves success and failure wording for authoritative `BUZZ_SUCCESS` and `BUZZ_FAILED` review statuses returned after finalization.

Teacher challenge editing stores the selected target in `submissions.group_number` and marks `group_challenged`. Because that number represents the challenged group rather than the submitter, the update transaction also deletes the target group's current Taipei-day global success counter so it immediately regains eligibility. During finalization, a challenged candidate consumes the slot for the submitter's original group parsed from the authoritative user display identity, while the challenge target remains unchanged for teacher display and Sheet answer attribution. Repeating the edit is idempotent because deleting an absent daily counter is a no-op.

`完成搶答並檢討` first stops further submissions and then finalizes the round transactionally. Candidates are joined from active attempts and pending submissions and evaluated by receipt order. A candidate whose group has reached the Taipei-day global limit is marked failed and skipped; the first eligible candidate atomically consumes the group's remaining slot and becomes the round's sole winner. Remaining candidates are marked failed. Repeating finalization is idempotent and returns the existing winner.

After the database decision commits, the winner alone is synchronized to Google Sheets. A failed Sheet write is recorded as retryable without changing the winner decision. Realtime review completion causes students to reload their own submission status and display only success or failure; the reason for failure remains hidden.

The classroom-level `完成搶答並檢討` action first stops all active buzz questions, then finalizes them sequentially in the displayed question order. Sequential processing makes the daily cross-classroom group limit deterministic. Each finalized winner is immediately synchronized to its own configured Excel/Google Sheet destination; failures are collected as retryable per submission while later questions continue processing.

Teacher question cards consume the current-round submissions in authoritative receipt order but render at most the first two distinct groups. The first submission for a group owns that group's visible slot; duplicate submissions from the same group and all groups after the first two remain persisted for review logic and auditing but are omitted from the card.

### 11. Make reset-all remove response history transactionally

The classroom-level `RESET_ALL` operation uses a destructive path distinct from single-question reset. For each eligible buzz question, it locks the question row, returns an active or locked state to `READY`, and deletes the question's `submissions` and `buzz_records` within one database transaction. A `READY` question also enters this path so residual response rows cannot survive an earlier state-only reset. Failure rolls back that question while the existing batch result reports the item failure and continues with other questions.

This cleanup intentionally leaves `question_buzz_rounds`, daily global group success counters, score history, and values already synchronized to Google Sheets unchanged. Those records have separate lifecycle controls and must not be silently rolled back by a question-state action. The teacher receives an explicit irreversible-deletion confirmation before the batch request, and successful completion clears cached answer cards.

## Risks / Trade-offs

- [Same-cell answers destroy visible markers] → Persist provenance at creation; duplicate detection does not depend on the marker remaining visible.
- [The source changes between scan and confirmation] → Use a short-lived scan token and revalidate marker values, selections, permissions, and existing bindings on confirmation.
- [An answer overwrites an existing value or formula] → Show the overwrite behavior in the teacher interface and perform the write only after teacher review accepts the winning submission.
- [Sheet synchronization fails after winner finalization] → Keep the committed winner and group count, mark the sync retryable, and expose the existing teacher retry action.
- [A large spreadsheet makes full grid reads expensive] → Read worksheet values only, impose configured scan bounds/marker limits, and report truncation rather than silently missing markers.
- [Spreadsheet names and worksheets can be renamed] → Store immutable spreadsheet configuration IDs plus worksheet names used at creation; surface a clear sync error if a destination worksheet later disappears.
- [Two teachers confirm overlapping scans concurrently] → Database uniqueness constraints are authoritative and the batch transaction converts conflicts into a duplicate-binding response.
- [READY-only edits may be restrictive] → It protects students and historical records; copying/recreating a package is preferable after activity begins.

## Migration Plan

1. Add nullable provenance and answer-worksheet fields so existing manual packages remain valid.
2. Add partial uniqueness constraints covering generated, non-archived bindings.
3. Deploy worksheet discovery, marker scan, validation, and confirmation APIs.
4. Deploy package-description update API and separate teacher/student serializers.
5. Deploy teacher scan/preview and package edit interfaces.
6. Verify existing manual packages still write to their legacy target ranges and remain readable.

Rollback disables the new routes and UI while retaining additive provenance columns. Generated packages remain ordinary readable packages with valid answer bindings; provenance must not be deleted.

