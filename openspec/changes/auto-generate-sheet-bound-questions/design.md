## Context

The current system stores a question package in `questions`, ordered subquestions in `question_items`, one package-level `sheet_config_id`, and one answer `target_cell` per item. Teachers manually enter package/item descriptions and target cells. `GoogleSheetsSyncService` can read spreadsheet grid values and write one or many answer cells, but it does not expose worksheet discovery or marker scanning. The legacy `PUT /api/questions` updates the aggregate prompt but does not update package items atomically.

The new workflow treats a marker cell as authoring metadata. It does not infer meaning from nearby text or images. The selected destination uses the same worksheet-relative A1 coordinate as the source marker, but may belong to another spreadsheet and worksheet.

## Goals / Non-Goals

**Goals:**

- Make marker detection deterministic and explicitly teacher-triggered.
- Support independent source and answer spreadsheet/worksheet selection.
- Preview and edit generated descriptions before persistence.
- Prevent duplicate imports and unintended answer-cell overwrites.
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

The answer worksheet must already exist. The service account must be able to read the source and write the answer destination. A nonblank destination blocks confirmation unless the destination is the same physical cell and its value is the marker being imported. This exception permits same-cell authoring without treating the marker as user data that would be overwritten unexpectedly.

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

## Risks / Trade-offs

- [Same-cell answers destroy visible markers] → Persist provenance at creation; duplicate detection does not depend on the marker remaining visible.
- [The source changes between scan and confirmation] → Use a short-lived scan token and revalidate marker values, selections, permissions, and occupied destinations on confirmation.
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

