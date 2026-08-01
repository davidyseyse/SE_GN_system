## 1. Database Schema and Migration

- [x] 1.1 Add idempotent schema changes for package-level sheet configuration, ordered `question_items`, normalized target-cell uniqueness, and submission sheet-sync status.
- [x] 1.2 Implement an idempotent backfill that creates one stable item for every legacy question, extracts valid legacy bindings, and reports malformed or missing bindings.
- [x] 1.3 Convert legacy submitted-answer values to item-ID-keyed answer objects while preserving aggregate IDs, rounds, attempts, scores, and history.
- [x] 1.4 Add migration verification queries/tests for aggregate-to-item coverage, ordering, duplicate cells, converted submissions, and safe reruns.

## 2. Package Domain and Persistence

- [x] 2.1 Add package/item models and connection-aware repositories that load and serialize ordered items with stable identities.
- [x] 2.2 Add canonical single-cell A1 range normalization and validation, including optional sheet-name prefixes and rejection of multi-cell ranges.
- [x] 2.3 Implement authoritative package validation for title, sheet configuration, item count, prompts, cells, and normalized duplicate bindings.
- [x] 2.4 Implement atomic package creation with one transaction for the retained aggregate and all item rows, including rollback tests.
- [x] 2.5 Update list, delete, reporting, and summary queries/serializers to return package titles and ordered items without breaking historical aggregate relationships.

## 3. Package HTTP Contracts

- [x] 3.1 Add a nested JSON request parser/model suitable for package and multi-answer arrays instead of extending the flat string-map parser.
- [x] 3.2 Add authorized create/list package endpoints and response contracts using `packageId`, title, shared settings, and ordered persistent items.
- [x] 3.3 Add a temporary legacy single-question creation adapter that produces a valid one-item package and rejects invalid bindings.
- [x] 3.4 Update shared and web API types for package creation, listing, lifecycle actions, buzz requests, package answers, and sync status.
- [x] 3.5 Add controller/contract tests for malformed nested JSON, authorization, unknown sheet configurations, invalid items, duplicate cells, and successful multi-item responses.

## 4. Package Lifecycle, Buzz, and Realtime

- [x] 4.1 Adapt lifecycle and batch action contracts to expose package terminology while retaining the existing aggregate state machine and per-aggregate result isolation.
- [x] 4.2 Adapt buzz, cancel, reset, Redis keys, and round handling to accept package IDs and explicitly reject item IDs as buzz targets.
- [x] 4.3 Preserve and test exactly one successful winner per package round under concurrent buzz requests and independence across simultaneously open packages.
- [x] 4.4 Publish package-scoped lifecycle, winner, reset, and submission events with `packageId`, round ID, and a temporary compatibility alias where required.
- [x] 4.5 Update teacher and student realtime reducers to index state by package and round and to clear only the ended package's winner and draft answers.

## 5. Package Answering and Google Sheets

- [x] 5.1 Implement package-answer validation against the authoritative item set, current package state, round, and winner, rejecting missing, duplicate, or foreign item IDs.
- [x] 5.2 Persist one item-ID-keyed answer object per package attempt in a transaction and preserve existing review, score, report, update, and delete behavior.
- [x] 5.3 Extend Google Sheets synchronization to send every item answer and bound cell in one values batch-update request.
- [x] 5.4 Persist sheet-sync success or retryable failure, make retries idempotent, and expose synchronization status without discarding accepted submissions.
- [x] 5.5 Add tests for complete submission, missing answers, non-winner submission, locked/reset rounds, stable item-to-cell mapping, batch synchronization, and retry behavior.

## 6. Teacher Package Authoring Interface

- [x] 6.1 Replace single prompt/cell state with package title, shared sheet configuration, and an ordered item draft array initialized to exactly one stable client-keyed item.
- [x] 6.2 Render the controls in the agreed order and add appended-item removal controls while never rendering a remove control for subquestion 1.
- [x] 6.3 Implement append, removal, consecutive display renumbering, responsive item layout, and preservation of stable React keys.
- [x] 6.4 Mirror package validation in the client, including required values, valid single-cell ranges, and normalized duplicate-cell detection.
- [x] 6.5 Submit one package request, reset to one blank item only on success, retain the selected sheet configuration after success, and preserve all draft input on failure.
- [x] 6.6 Update the teacher package list and package-level open, stop, reset, review, and delete controls.
- [x] 6.7 Add UI tests for initial one-item state, append/remove rules, non-removable first item, validation, success reset, failure preservation, and payload ordering.

## 7. Student Package Interface

- [x] 7.1 Render each visible package with its title, ordered subquestion prompts, package status, and exactly one package-level buzz button.
- [x] 7.2 Render one answer input per item only for the package winner and submit all item-ID-keyed answers through one package action.
- [x] 7.3 Remove the visible realtime message/event-history panel while retaining the realtime hook, connection indicator, resync, and reducer-driven updates.
- [x] 7.4 Add student UI tests for package visibility, one buzz action, winner multi-answer inputs, non-winner restrictions, and absence of the event feed.
- [x] 7.5 Add realtime regression tests proving package state and winner updates still render without manual refresh after the feed removal.

## 8. Integration, Compatibility, and Verification

- [x] 8.1 Add end-to-end coverage from package creation through buzz winner, complete multi-answer submission, per-cell synchronization, review, reset, and a later new round.
- [x] 8.2 Verify migrated one-item packages remain listable, buzzable, answerable, reportable, and correctable when legacy bindings are invalid.
- [x] 8.3 Run API build/tests, web type-check/build/tests, strict OpenSpec validation, and targeted concurrent buzz/load validation.
- [x] 8.4 Document deployment ordering, database backup and verification steps, compatibility-adapter monitoring, sync-failure recovery, and non-destructive rollback.
