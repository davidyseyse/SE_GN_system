## Why

The current authoring flow can create only one independently buzzed question with one Google Sheets cell binding. Teachers need to create one titled question package containing multiple cell-bound subquestions, while students compete once for the right to answer the entire package.

## What Changes

- Replace the single-question authoring form with a question-package form that retains the shared sheet configuration selector, adds a package title, and starts with exactly one non-removable subquestion.
- Allow teachers to append subquestions dynamically and remove any appended subquestion while always retaining subquestion 1.
- Give every subquestion its own prompt and target-cell binding, validate the complete package, and create the package and all subquestions atomically in one request.
- Change buzz, lifecycle, winner, reset, and completion semantics from an individual question to an entire question package.
- Allow the package buzz winner to answer all subquestions in one submission flow and synchronize each answer to its subquestion's bound Google Sheets cell.
- Remove the visible student-facing realtime message feed while retaining realtime connectivity and event-driven state updates.
- **BREAKING** Existing APIs and clients that treat each authored prompt as an independently buzzed question must adopt package-level lifecycle and submission contracts.

## Capabilities

### New Capabilities

- `question-package-management`: Teacher authoring, validation, ordering, atomic persistence, shared sheet configuration, and per-subquestion cell binding for titled question packages.
- `question-package-buzz-and-answer`: Package-level lifecycle, one buzz competition and winner per package round, multi-subquestion answering, and per-cell Google Sheets synchronization.
- `student-live-interface`: Student presentation of question packages and removal of the visible realtime event feed without disabling realtime behavior.

### Modified Capabilities

None. The repository currently has no main capability specs to amend; the package behavior is introduced as new capability deltas.

## Impact

- Web: teacher authoring state and controls, package/question list presentation, student buzz and multi-answer UI, API types, and realtime reducers.
- API: nested request parsing, package creation and query endpoints, lifecycle/buzz/submission contracts, authorization, transaction boundaries, serialization, reporting, and realtime payloads.
- PostgreSQL: package and subquestion persistence, package-level round and buzz relationships, ordering, cell bindings, constraints, and legacy-question migration or compatibility handling.
- Google Sheets: one shared configuration per package with one target range per subquestion and one answer write per binding.
- Tests: dynamic form behavior, validation, atomic creation, package lifecycle/concurrency, multi-answer submission, sheet synchronization, realtime behavior, and legacy compatibility.
