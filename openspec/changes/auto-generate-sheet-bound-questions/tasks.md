## 1. Persistence and migration

- [x] 1.1 Add nullable source sheet configuration, source worksheet, answer worksheet, source cell, and source marker persistence while preserving existing manual packages.
- [x] 1.2 Add repository methods and partial uniqueness constraints for active source and answer bindings.
- [x] 1.3 Add an idempotent migration and verification queries covering legacy rows and generated provenance.

## 2. Google Sheets discovery and scanning

- [x] 2.1 Add worksheet discovery and bounded worksheet-value reading to the Google Sheets service.
- [x] 2.2 Implement exact marker parsing, normalization, grouping, numeric ordering, duplicate detection, and numbering-gap warnings.
- [x] 2.3 Implement independent source-read and destination-write validation, including the same-physical-marker-cell exception.
- [x] 2.4 Add unit tests for marker grammar, images being ignored, grouping, invalid markers, gaps, and same-coordinate mapping.

## 3. Scan and confirmation APIs

- [x] 3.1 Add teacher-authorized scan contracts returning editable preview data, warnings, conflicts, and a short-lived verification token.
- [x] 3.2 Add confirmation revalidation and transactional batch creation using the existing package aggregate and item identities.
- [x] 3.3 Enforce source and answer binding uniqueness under concurrent confirmations and return actionable conflict responses.
- [x] 3.4 Add API tests for different spreadsheets, identical worksheets, occupied destinations, changed scans, duplicate imports, authorization, and atomic rollback.

## 4. Package description editing

- [x] 4.1 Add transactional repository support for updating a package title and its complete item-description set.
- [x] 4.2 Add a package-oriented update endpoint with READY-state, exact-membership, nonblank-text, authorization, and binding-preservation validation.
- [x] 4.3 Add tests for successful edits, cancellation/no request, invalid item sets, active/historical state rejection, and rollback.

## 5. Role-specific serialization

- [x] 5.1 Separate teacher and student package serializers so spreadsheet provenance and bindings are never emitted to students.
- [x] 5.2 Audit realtime payloads and student endpoints for source, destination, marker, configuration, and target-cell leakage.
- [x] 5.3 Add contract tests proving teacher visibility and student data minimization for manual and generated packages.

## 6. Teacher interface

- [x] 6.1 Add independent source Google Sheet/worksheet and answer Google Sheet/worksheet selectors with no mapping-mode control.
- [x] 6.2 Add the explicit `自動偵測生成題目` action and preview states for loading, empty results, warnings, blocking errors, and already imported markers.
- [x] 6.3 Add editable generated titles/item descriptions, package inclusion controls, confirmation, and success/error handling.
- [x] 6.4 Add the `編輯` action and inline READY-package title/item-description editor without structural or binding controls.
- [x] 6.5 Add responsive UI and state tests for scanning, preview edits, confirmation, same-sheet selection, description editing, and cancellation.

## 7. Verification and operations

- [x] 7.1 Run API, web, migration, and end-to-end validation suites, including existing manual package and Google Sheets answer-sync regression coverage.
- [x] 7.2 Document service-account read/write permission requirements, marker syntax, duplicate behavior, same-cell overwrite behavior, scan limits, and rollback steps.

## 8. Sheet configuration deletion

- [x] 8.1 Delete answer/source-dependent packages and the selected Sheet configuration atomically, returning the deleted package count.
- [x] 8.2 Add destructive confirmation, result/error feedback, and client-state cleanup to the teacher interface.
- [ ] 8.3 Add regression coverage and documentation, then run API/web builds and strict OpenSpec validation.

## 9. Occupied answer-cell overwrite

- [x] 9.1 Stop reading or rejecting existing answer-cell values during generated-question scan and confirmation while retaining write-permission and duplicate-binding validation.
- [x] 9.2 Explain in the teacher preview that an accepted student answer overwrites existing destination values or formulas.
- [x] 9.3 Update regression contracts and documentation, then run API/web builds and strict OpenSpec validation.

## 10. Slash-separated group score statistics

- [x] 10.1 Parse slash-separated answers and award each distinct group at most one point per cell while accumulating points across cells.
- [x] 10.2 Present teacher statistics as points and add multi-group and same-group deduplication regression coverage.
- [x] 10.3 Update documentation and run API/web builds plus strict OpenSpec validation.

## 11. Teacher-finalized buzz answers

- [x] 11.1 Persist valid buzz answers as pending without early winner selection, group counting, bonus scoring, or Google Sheets synchronization.
- [x] 11.2 Add an idempotent teacher finalization operation that locks the round, skips groups at the daily global limit, selects the fastest eligible submitted attempt, and records all final outcomes.
- [x] 11.3 Synchronize only the finalized winner to Google Sheets and publish review completion for student refresh.
- [x] 11.4 Update teacher and student interfaces so submission shows received first and success/failure appears only after review.
- [x] 11.5 Add regression coverage and documentation, then run API/web builds and strict OpenSpec validation.

## 12. Reliable pending-submission feedback

- [x] 12.1 Treat a duplicate request for an existing same-round pending answer as successful and preserve one submission row.
- [x] 12.2 Scope student submission and review notices by question round so a prior result is not displayed after reopening.
- [x] 12.3 Add regression coverage and run API/web builds plus strict OpenSpec validation.

## 13. Pre-review submission feedback

- [x] 13.1 Remove final buzz-failure wording from all pre-review rejection paths and reserve it for authoritative review results.
- [x] 13.2 Add regression coverage and run web tests/build plus strict OpenSpec validation.

## 14. Batch buzz completion and Sheet synchronization

- [x] 14.1 Replace the classroom stop-all action with batch completion that stops and sequentially finalizes all active buzz questions.
- [x] 14.2 Synchronize every finalized winner to its configured Sheet destination and retain per-submission retry actions.
- [x] 14.3 Update documentation and regression coverage, then run web/API builds plus strict OpenSpec validation.

## 15. Fastest two-group teacher answer display

- [x] 15.1 Select the first submission from each of the two fastest distinct groups without changing persisted submissions.
- [x] 15.2 Render only the selected submissions in each teacher question card.
- [x] 15.3 Add regression coverage and documentation, then run the web build and strict OpenSpec validation.

## 16. Reset-all response cleanup

- [x] 16.1 Delete each buzz question's submissions and buzz records transactionally while resetting it, including residual records on `READY` questions.
- [x] 16.2 Warn teachers that reset-all permanently deletes response records and clear cached teacher answer cards after confirmation.
- [x] 16.3 Add regression coverage and documentation, then run API/web builds and strict OpenSpec validation.

## 17. Group-only Sheet statistics display

- [x] 17.1 Replace score summaries and per-group points with a distinct successful-group list while retaining the existing API contract.
- [x] 17.2 Update teacher copy, styling, regression coverage, and documentation for group-only presentation.
- [x] 17.3 Run web tests/build and strict OpenSpec validation.

## 18. Challenged-group buzz eligibility restoration

- [x] 18.1 Persist challenge edits and restore the challenged group's current-day global eligibility in one transaction.
- [x] 18.2 Attribute finalized challenge success to the submitter's original group while preserving the challenged group for display and Sheet synchronization.
- [x] 18.3 Add regression coverage and documentation, then run API/web builds and strict OpenSpec validation.
