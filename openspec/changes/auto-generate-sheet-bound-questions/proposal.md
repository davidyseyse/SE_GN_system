## Why

Teachers currently create every question package and cell binding manually even when a Google Sheets template already marks the intended answer cells. This is slow, error-prone, and makes repeated classroom setup unnecessarily tedious. Teachers also cannot correct package or subquestion descriptions after a package has been created.

## What Changes

- Add a teacher-triggered action that scans one selected Google Sheet worksheet for exact `[[Qn]]` and `[[Qn-m]]` marker values and produces an editable preview instead of immediately creating questions.
- Group `[[Qn-m]]` markers into package `Qn`, while treating `[[Qn]]` as a one-item package, using the normalized marker codes as the default package and item descriptions without reading surrounding cell text or images.
- Let the teacher independently select a source spreadsheet/worksheet and an answer spreadsheet/worksheet; bind every detected source cell to the same A1 position in the selected answer worksheet.
- Permit the source and answer selections to refer to different spreadsheets or to the exact same spreadsheet and worksheet.
- Validate permissions, worksheets, duplicate markers, and previously imported source or answer bindings before confirmation while allowing an existing answer-cell value or formula to be overwritten by the later accepted student answer.
- Persist source provenance separately from the answer binding so repeated scans can identify existing imports even if a same-cell marker is later overwritten by an answer.
- Add teacher editing of an existing package title and its item descriptions without changing item identity, order, provenance, or answer-cell bindings.
- Keep spreadsheet names, worksheet names, source cells, and answer bindings teacher-only; student contracts and views expose only package and item descriptions.
- Identify slash-separated `answer_G<number>` segments in teacher Sheet statistics and display only the distinct successful groups without scores or totals.
- Defer buzz winner selection and Google Sheets answer synchronization until the teacher invokes `完成搶答並檢討`; student submissions remain pending and reveal only that they were received until review completes.
- Restore a challenged group's current-day buzz eligibility while charging a finalized challenged answer to the challenger's original group.

## Capabilities

### New Capabilities

- `sheet-marker-question-generation`: Marker scanning, grouping, preview, cross-spreadsheet same-position mapping, validation, provenance, and confirmed package creation.
- `question-package-description-editing`: Safe post-creation editing of package and subquestion descriptions while preserving structural and binding identity.

### Modified Capabilities

- `question-package-management`: Extend package management with generated-package provenance and teacher-only binding visibility.

## Impact

- Web: teacher source/destination selectors, explicit scan button, preview and validation states, confirmed batch creation, and inline package-description editing.
- API: Google Sheets worksheet discovery and marker scanning, scan/confirm contracts, duplicate-import checks, package-description update contract, and teacher/student response separation.
- Buzz workflow: persist every valid submitted attempt, finalize the fastest eligible group on teacher review, enforce the daily cross-classroom group limit at finalization, and publish the final success/failure result.
- PostgreSQL: source spreadsheet configuration, source worksheet/cell/marker provenance, answer worksheet names, uniqueness constraints, and transactional description updates.
- Google Sheets: read access to the selected source spreadsheet and write validation for the independently selected answer spreadsheet; images are ignored.
- Tests: marker grammar and grouping, same-position mapping across spreadsheets, same-source-and-destination behavior, validation and duplicate prevention, authorization, editing restrictions, and student data minimization.

