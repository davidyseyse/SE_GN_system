# Marker-generated question packages

## Google service-account access

The service account configured by `GOOGLE_APPLICATION_CREDENTIALS` must be shared on both selected spreadsheets. It needs read access to the source spreadsheet and edit access to the answer spreadsheet. The source and answer may be the same spreadsheet and worksheet.

## Marker syntax

- `[[Q1]]` creates package `Q1` with one item named `Q1`.
- `[[Q1-1]]`, `[[Q1-2]]` create package `Q1` with items `Q1-1`, `Q1-2`.
- Matching trims whitespace and ignores case, then normalizes to uppercase.
- Zero, negative, partial, embedded, and formula values are ignored.
- Images, formatting, notes, and nearby text are not imported.

The teacher explicitly selects a source spreadsheet/worksheet and an answer spreadsheet/worksheet, then presses `自動偵測生成題目`. Each marker maps to the identical A1 coordinate in the answer worksheet. There is no configurable offset or mapping mode.

## Preview and validation

Scanning does not persist questions. The preview lets a teacher edit package and item descriptions or exclude packages before confirmation. Duplicate markers, mixed standalone/subquestion markers, previously imported sources, and previously bound answer cells block creation. Existing answer-cell values and formulas do not block creation; they remain in place until the teacher completes buzz review and the finalized winning answer overwrites them. Numbering gaps are warnings.

Confirmation revalidates the source and creates the selected batch transactionally. The scan token expires after ten minutes. A worksheet change requires a new scan. Scans are limited to 500 markers.

## Same-cell behavior

When source and answer resolve to the same physical cell, the marker itself is allowed as the initial destination value. Existing values and formulas are also allowed in other destination cells. A successful student answer later overwrites the destination. Source spreadsheet, worksheet, cell, and marker provenance remain in PostgreSQL, so duplicate-import detection does not depend on the marker remaining visible.

## Visibility and editing

Spreadsheet provenance and cell bindings are teacher-only. Student API and realtime views contain package and item descriptions but no source or answer location. Teachers can use `編輯` to change the title and complete item-description set only while the package is `READY`; identities, ordering, and bindings cannot be changed.

## Deployment and rollback

Apply `infra/postgres/init.sql` before deploying the API and run `infra/postgres/verify-generated-question-bindings.sql`. The migration is additive and legacy/manual packages do not require provenance.

Rollback the API and web application without dropping `question_item_sheet_bindings`. Generated packages remain readable as ordinary packages and their provenance must be retained for audit and duplicate prevention.

