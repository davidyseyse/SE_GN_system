## Context

The current system stores a question lifecycle state separately from persistent buzz records and a per-question round counter. A successful buzz emits a rich realtime event but intentionally leaves the question in `BUZZ_OPEN` so the winner can submit. The teacher client refreshes questions after realtime events, yet the question list does not expose the active winner or current-round submission state, so a refresh cannot reconstruct what the teacher needs to see.

New questions are explicitly created as `DRAFT`, while the only practical use of the teacher's “設為未開放” action is `DRAFT → READY`. Reset is a different operation: it releases current interaction keys, returns an active or locked question to `READY`, and notifies clients to clear ended-round UI state.

The change crosses the React teacher client, REST APIs, realtime contracts, question lifecycle state machine, PostgreSQL repositories, Redis locks, and existing round-scoped student behavior. See `proposal.md` and the capability specs for externally visible requirements.

## Goals / Non-Goals

**Goals:**

- Preserve `READY` as “prepared but not open” and `LOCKED` as “stopped with the current round retained”.
- Provide an authoritative current-round teacher view that survives refresh, reconnect, and in-memory event loss.
- Keep single-question and classroom batch operations consistent with the same state and round rules.
- Make stop and reset behavior explicit and independently testable.
- Prevent batch operations from silently reporting full success when individual questions are skipped or fail.

**Non-Goals:**

- Merging `READY` and `LOCKED`.
- Automatically reviewing, scoring, publishing, editing, or deleting a submitted answer when a question is stopped.
- Deleting historical buzz attempts or submissions during reset.
- Changing the student winner-selection concurrency algorithm.
- Adding a new external service or client-side state management framework.

## Decisions

### 1. Create questions directly in `READY`

Question creation will persist `READY` rather than `DRAFT`. The teacher client will remove “設為未開放” and retain “重置本題”. Legacy `DRAFT` rows will be converted to `READY` by an idempotent database migration; the server will retain the legacy enum value during rollout so older data cannot make startup or reads fail.

Alternative considered: create as `DRAFT` and immediately call the transition endpoint. This keeps an unnecessary intermediate state, introduces a second request that can fail independently, and does not meet the requirement that creation itself finishes in the usable initial state.

### 2. Keep lifecycle state and current-round activity separate

`questions.state` remains the lifecycle authority. The teacher-facing question response will additionally include a derived current-round status assembled from:

- the current round identifier,
- the active winner for that round,
- the winner's display name,
- the current-round submission and review status.

The derived status will distinguish at least `WAITING_FOR_BUZZ`, `WINNER_ANSWERING`, `SUBMITTED_PENDING_REVIEW`, `STOPPED`, and `NOT_OPEN`. It will not add these values to the question lifecycle enum.

Alternative considered: set the question to `LOCKED` as soon as a winner is chosen. This would conflict with the current submission authorization, which accepts the winner's answer only while the question remains open, and would conflate “winner chosen” with “teacher stopped the round”.

### 3. Realtime events provide immediacy; authoritative reads provide recovery

The teacher client will optimistically update the affected question from round-scoped realtime events and reconcile by fetching authoritative current-round data. Event payloads will continue to include `questionId` and `roundId`; winner and submission events will include the display/status fields needed for immediate rendering. Events from a non-current round will be ignored.

The authoritative response, not the in-memory event log, determines state after initial load or reconnect. This avoids making correctness depend on the process-local realtime broker retaining events across a server restart.

Alternative considered: derive teacher status exclusively by replaying realtime events. It is simpler initially but cannot reconstruct the winner after server restart or lost event history.

### 4. Preserve “完成搶答並檢討” as a compound single-question action

For an open question, the action transitions the question to `LOCKED`, refreshes authoritative state, and selects the question so its submissions appear in the review area. For an already locked question it only selects/reloads it. It does not call the submission review endpoint, assign scores, change answers, publish results, reset the round, or change classroom state.

The UI will describe `LOCKED` as stopped/awaiting review so the action's observable result is clear.

Alternative considered: remove the action and rely on reset. Reset has different round semantics and clears client interaction state, so it cannot safely replace a stop-and-review operation.

### 5. Implement classroom operations as explicit backend batch commands

One authorized classroom-scoped endpoint or command surface will accept an action of `OPEN_ALL`, `STOP_ALL`, or `RESET_ALL`. Each action uses a shared per-question domain operation:

- `OPEN_ALL`: only `READY` buzz questions become `BUZZ_OPEN`; each starts one new round.
- `STOP_ALL`: only `BUZZ_OPEN` or `ANSWER_OPEN` questions become `LOCKED`; round and winner data remain intact.
- `RESET_ALL`: active or locked buzz questions release current Redis interaction keys, become `READY`, and emit reset events; finalized states are skipped.

Each item returns `CHANGED`, `UNCHANGED`, `SKIPPED`, or `FAILED`, along with prior/resulting state, current/new round identifier, and reason where applicable. Authorization and classroom resolution occur before any item is mutated.

Alternative considered: have the browser call the single-question endpoint repeatedly. That approach creates avoidable partial progress during client interruption, multiplies network requests, and cannot provide a coherent server-generated result summary.

### 6. Use per-question transactions and explicit partial-result semantics

Question state and PostgreSQL round creation for one item will commit atomically in a database transaction. Items are processed independently so one malformed or concurrently changed question does not roll back otherwise valid questions; failures are reported per item.

Redis changes and realtime publication occur only after the corresponding database item commits. If Redis reset cleanup fails, that item is reported failed and remains eligible for retry; events are not published as successful before required cleanup completes. Repeated batch requests are state-aware: already-open questions do not gain another round, already-locked questions do not reset, and ineligible questions are skipped.

Alternative considered: one transaction for the entire classroom. It gives all-or-nothing behavior but holds a larger transaction across unrelated questions and still cannot atomically cover Redis and WebSocket delivery. Per-item atomicity plus explicit outcomes gives safer retries and clearer operational behavior.

### 7. Make status presentation accessible and action availability state-driven

Teacher question cards will display a textual status badge, winner name when applicable, and a visual style/icon. Color will supplement rather than replace text. Single-question buttons will be enabled or shown only when their action is valid. Batch controls will show a confirmation for destructive reset and summarize changed/skipped/failed counts after completion.

## Risks / Trade-offs

- [Current repository methods open separate database connections, making per-question state-and-round atomicity unavailable] → Add a transaction-capable repository/service boundary and pass one connection through the state update and round increment for each opened question.
- [A winner or submission can race with stop/reset] → Re-read and conditionally update the question state inside the item transaction; enforce state checks on buzz and submission writes and test concurrent boundaries.
- [PostgreSQL and Redis cannot share one atomic transaction] → Order reset cleanup and database mutation deliberately, make reset retry-safe, and never publish a success event before required operations succeed.
- [Legacy `DRAFT` migration changes existing stored state] → Use an idempotent targeted migration, measure affected rows, and keep rollback SQL that restores only rows recorded by the migration if rollback is required.
- [Adding winner/submission details to every question can create N+1 queries] → Query current rounds, active winners, users, and submissions in classroom-scoped batches or a joined projection.
- [Realtime and fetch responses may arrive out of order] → Compare `roundId` and prefer authoritative fetch results; ignore older-round events.
- [Batch open may expose many questions simultaneously to students] → Require an explicit teacher action and return the exact opened set; do not include non-buzz or finalized questions.

## Migration Plan

1. Add the authoritative current-round projection and batch-control backend behavior behind compatible response fields/endpoints.
2. Add database transaction support needed for per-question state and round consistency.
3. Run an idempotent migration converting existing `DRAFT` questions to `READY`, recording or reporting the affected identifiers/count.
4. Deploy the teacher client that removes “設為未開放”, renders derived status, and exposes the three batch actions.
5. Validate single-question stop/review, single and all reset, batch open/stop/reset, realtime updates, refresh/reconnect recovery, and concurrent requests.
6. Monitor batch failures and unexpected remaining `DRAFT` counts.

Rollback keeps the additive current-round response fields harmless to older clients, removes or hides batch controls, and restores the previous question creation state. If the data migration must be reversed, only rows identified as migrated and not subsequently advanced are eligible to return to `DRAFT`.
