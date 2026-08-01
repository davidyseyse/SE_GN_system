## Context

See `proposal.md` for motivation and the three delta specs for observable behavior. Today, `questions` is the aggregate referenced by lifecycle state, buzz rounds, buzz records, submissions, scores, reports, and realtime payloads. Its `prompt` is the displayed question text, while `sheetConfigId` and `targetCell` are embedded in `publication_settings`. The web client and API accept one prompt and one answer per `questionId`, and the API uses a flat string-map JSON parser.

The change crosses React UI, HTTP contracts, PostgreSQL, concurrency-controlled buzz handling, submissions, realtime events, and Google Sheets. Preserving the existing lifecycle aggregate identity is therefore safer than replacing every `question_id` relationship.

## Goals / Non-Goals

**Goals:**

- Preserve one stable aggregate identifier for package lifecycle, rounds, buzzes, scoring, reporting, and realtime routing.
- Store ordered subquestions with stable identities and normalized cell bindings.
- Validate and persist a package and its subquestions atomically.
- Migrate existing single questions into one-item packages without discarding history.
- Keep realtime transport active while removing only the student event-history panel.
- Make package submissions and multi-cell synchronization retry-safe.

**Non-Goals:**

- Introducing independent lifecycle, buzz, timing, or scoring state for individual subquestions.
- Allowing different sheet configurations within one package.
- Adding collaborative editing, package templates, or draft autosave.
- Redesigning authentication, classroom membership, or the underlying realtime transport.
- Supporting multi-cell ranges as a subquestion target; each binding identifies one Google Sheets cell.

## Decisions

### 1. Reinterpret the existing question aggregate as a package

The existing `questions.id` remains the package aggregate identifier. `questions.prompt` becomes the package title, and existing state, ordinal, score, timing, round, buzz, submission, and reporting relationships remain anchored to that identifier.

A new `question_items` table stores:

- `id UUID PRIMARY KEY`
- `question_id UUID REFERENCES questions(id) ON DELETE CASCADE`
- `ordinal INTEGER`
- `prompt TEXT`
- `target_cell TEXT`
- uniqueness on `(question_id, ordinal)`
- uniqueness on `(question_id, normalized_target_cell)`

`questions.sheet_config_id` becomes a nullable foreign key for migration compatibility but is required by the new create-package API. Cell values are trimmed and normalized to a canonical case before duplicate checks. The server accepts only a single-cell A1 range, optionally prefixed by a sheet name, and rejects multi-cell ranges.

Why: this keeps the heavily referenced aggregate and its concurrency semantics intact while separating package title from ordered answerable content.

Alternative considered: create a new `question_packages` table and convert existing `questions` into child rows. That model is conceptually direct but requires moving or rewriting every lifecycle, round, buzz, submission, score, report, and realtime foreign key and creates a substantially riskier migration.

Alternative considered: keep subquestions in `publication_settings` JSON. That avoids a table but provides weak validation, unstable item identity, awkward ordering updates, and no relational uniqueness for bindings.

### 2. Add package-oriented contracts while retaining a migration bridge

Introduce package-oriented endpoints and client types:

```json
POST /api/question-packages
{
  "classroomCode": "ABC123",
  "title": "Package title",
  "sheetConfigId": "uuid",
  "items": [
    { "prompt": "Subquestion 1", "targetCell": "A1" }
  ],
  "score": 10,
  "answerTimeLimitSeconds": 30
}
```

The response returns `id`, `title`, shared package properties, and ordered items with persistent item IDs. Listing, lifecycle, batch, buzz, reset, delete, reporting, and realtime contracts expose `packageId`; during migration, server-side internal methods may retain `questionId` naming and realtime payloads may include a temporary `questionId` alias equal to `packageId`.

The existing single-question create endpoint is retained only as a compatibility adapter during rollout: it constructs a one-item package when its binding is valid. New web code uses only the package endpoint. Removal of the adapter is deferred until all clients and fixtures have migrated.

Nested bodies must be parsed by a real JSON request model/parser rather than extending the flat `Map<String, String>` request representation.

Why: a dedicated contract reflects the new aggregate and supports arrays and item identity without forcing an all-at-once internal rename.

Alternative considered: call the existing create endpoint once per item. That cannot guarantee one aggregate, one buzz target, contiguous ordering, or atomic creation.

### 3. Use one database transaction for create and package-answer persistence

A package service owns transaction boundaries. Creation performs authorization and full validation before opening a transaction, then inserts the package aggregate and all item rows through connection-aware repository methods. Any database failure rolls back all inserts.

Package submission accepts answers keyed by persistent item ID:

```json
{
  "classroomCode": "ABC123",
  "packageId": "uuid",
  "answers": [
    { "itemId": "uuid", "answer": "..." }
  ]
}
```

The service locks or verifies the current package round and winner, loads the authoritative item set, rejects missing, duplicate, or foreign item IDs, and stores a single JSON object keyed by item ID in `submissions.submitted_answer`. It never trusts display order as identity.

Why: the package is the consistency boundary. Validation against the authoritative item set prevents partial answers and index-shift binding errors.

Alternative considered: one submission row per subquestion. That would duplicate winner/round/attempt state and permit partially persisted package answers unless another parent submission aggregate were added.

### 4. Keep lifecycle and buzz concurrency at the existing aggregate

Existing state transitions, Redis keys, PostgreSQL rounds, successful-winner uniqueness, resets, and batch actions continue to operate on the retained aggregate ID. Public naming changes from question to package, and subquestion IDs are never accepted by buzz or lifecycle endpoints.

Realtime reducers index UI state by `(packageId, roundId)`. Events identify the affected package and never create independent item lifecycle state. Draft answers are stored per package/item and cleared only for the ended package round.

Why: current per-question isolation already has the desired concurrency boundary once that aggregate represents a package.

### 5. Synchronize one submission with one Google Sheets batch request

The server resolves the package's one sheet configuration and maps every authoritative item ID to its target cell. It sends the cells and submitted values through the Google Sheets values batch-update endpoint in one request rather than issuing unrelated calls per item.

Submission persistence and an associated sheet-sync status are committed before the external call. A failed sync is recorded as retryable and reported without deleting the accepted classroom submission. Repeating synchronization is idempotent because it overwrites the same cells with the same submission values. Successful synchronization marks the submission synced.

Why: PostgreSQL and Google Sheets cannot share a transaction. Persist-first plus explicit retry state prevents a transient Google failure from losing the student's accepted answers, while one batch request reduces partial remote updates.

Alternative considered: write Sheets before committing PostgreSQL. A later database failure would leave remote cells updated without a corresponding submission. Alternative considered: fail and roll back the database after a Sheets error. That is misleading because a remote request may have succeeded despite a lost response.

### 6. Model the authoring form as an ordered draft array with stable client keys

The React form state contains `title`, `sheetConfigId`, and an item array initialized to one empty entry. Each draft item has a client-only UUID, prompt, and target cell. Item 1 has no remove control; every appended item has one. Removing an appended item changes display numbering only and does not reuse client keys.

Client validation mirrors server rules to guide the teacher, but the server remains authoritative. The create button is disabled until the draft is complete. Success resets title and items to one blank item; failure preserves all draft state. The sheet selection is retained after success to support consecutive package creation against the same form configuration.

Why: stable keys prevent React from moving input values or focus when an intermediate item is removed.

### 7. Render package interaction while keeping realtime plumbing invisible

Teacher and student lists render package title plus ordered item prompts. The student package card contains one buzz button. After winning, it renders one controlled answer input per item and one package submit action.

The student page removes the visible realtime-message/event-history panel and its mapping over recent events. The realtime hook, connection-status indicator, resync behavior, and event reducers remain. Tests distinguish DOM removal from transport removal.

Why: the feed is presentation-only; package correctness still depends on realtime events.

### 8. Migrate each legacy question into a one-item package

Migration adds the package sheet-config column and item table, then creates one item for every existing question:

- Package title remains the legacy `questions.prompt`.
- Item prompt copies the same legacy prompt.
- Item target cell and package sheet configuration are extracted from legacy `publication_settings` when valid.
- Existing aggregate IDs, ordinals, states, rounds, buzz records, submissions, scores, and audit history remain unchanged.
- Legacy submissions are converted to item-keyed answer objects for the generated item.

Legacy records with missing or invalid sheet bindings remain readable and operable but must be corrected before binding-dependent synchronization. New package creation never permits those invalid states.

Why: this preserves historical identity and provides deterministic compatibility without inventing titles or discarding answers.

## Risks / Trade-offs

- [The `questions` name no longer matches its package semantics] → Keep the physical name during this change to avoid a high-risk foreign-key migration; expose package terminology at API and UI boundaries and consider a later database rename.
- [Legacy `publication_settings` values may be malformed] → Make migration idempotent, record affected IDs, preserve the original JSON, and leave invalid bindings explicitly unsynchronized for correction.
- [PostgreSQL commit and Google Sheets update are not atomic] → Persist sync status, use one idempotent batch update, expose failure, and allow retry.
- [A breaking payload rename can desynchronize mixed-version clients] → Deploy additive package endpoints and temporary identifier aliases before switching clients; remove compatibility only in a later change.
- [Large packages increase payload and UI size] → Validate a configurable maximum item count at both boundaries and render ordered blocks responsively; choose the initial limit during implementation configuration without changing package semantics.
- [Package-level score meaning may be misunderstood] → Preserve the existing aggregate score and report one package submission; do not introduce independent per-item scores in this change.
- [Strict single-cell validation may reject previously accepted arbitrary ranges] → Apply strict validation to new/edited bindings while preserving legacy records until corrected.

## Migration Plan

1. Back up PostgreSQL and inventory invalid or missing legacy sheet bindings.
2. Deploy idempotent schema additions for package sheet configuration, item rows, submission sync status, indexes, and constraints that can initially tolerate legacy nulls.
3. Backfill one stable item per existing aggregate and convert legacy submission answers to item-keyed objects; verify counts and foreign-key coverage.
4. Deploy additive package repositories, services, endpoints, serializers, Google Sheets batch synchronization, and compatibility aliases.
5. Deploy teacher and student clients using package contracts; keep the legacy create adapter during the rollout.
6. Enable stricter constraints for newly created packages and monitor package creation, buzz, submission, and sync failures.
7. After all supported clients have migrated, schedule legacy endpoint and alias removal as a separate change.

Rollback keeps the additive tables and columns so migrated history is not destroyed. Revert clients and route traffic to the compatibility adapter; do not drop item data or reverse converted submissions until the new version is confirmed retired.
