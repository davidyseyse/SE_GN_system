## Context

See `proposal.md` for motivation and `specs/unanswered-buzz-lock-expiration/spec.md` for observable behavior. The API currently stores a successful winner in both Redis and `buzz_records`, but the Redis TTL is hard-coded to 300 seconds and the database winner remains active until manual cancellation or reset. `questions.answer_time_limit_seconds` already exists and new packages default it to 30, yet it is not enforced after a buzz.

Both legacy and package submission controllers currently authorize the winner and insert the submission through separate repository calls and database connections. A background timer that merely checks for a submission could therefore expire a winner between authorization and persistence. The solution must make the deadline durable, arbitrate expiration versus persistence in PostgreSQL, and use Redis only as the fast contention lock.

## Goals / Non-Goals

**Goals:**

- Use the existing per-question/package answer limit as N and expose it in teacher authoring.
- Release an unanswered winner automatically while keeping the same round open.
- Make deadline enforcement, submission, and timeout processing race-safe and restart-safe.
- Reuse current cancellation reducers so all connected clients immediately return to the available-to-buzz state.
- Preserve historical buzz records and existing nullable legacy data.
- Show the active student winner a countdown derived from the server-snapshotted deadline and restore it after client refresh or reconnect.

**Non-Goals:**

- Ending or resetting a round when a winner times out.
- Making the client countdown authoritative for lock release or answer acceptance.
- Reversing an already awarded buzz bonus when a winner times out.
- Automatically expiring ordinary `ANSWER_OPEN` participation that has no exclusive buzz winner.
- Backfilling automatic deadlines for active legacy winners that were created before deployment.

## Decisions

### 1. Snapshot the deadline on the winning buzz record

Add nullable `buzz_records.answer_deadline_at TIMESTAMPTZ`. When a buzz becomes the successful active winner, calculate `received_at + answer_time_limit_seconds` once and store it in the same insert. A null deadline means automatic expiration is disabled for that record.

This is preferable to deriving the deadline from the current question row during every sweep because a later teacher edit must not shorten or extend an already running winner window. It also makes restart recovery a direct indexed database query.

### 2. Keep PostgreSQL authoritative and Redis as the fast mutex

Redis continues to select one concurrent buzz winner, but its winner-key TTL is extended beyond the configured deadline by a cleanup grace period. Its value is the server-generated buzz-record ID rather than a client-supplied request ID. Buzz admission also respects an existing active database winner so Redis eviction or restart cannot create a conflicting apparent winner. Timeout completion updates PostgreSQL first, then removes the round key through a Lua compare-and-delete operation that succeeds only when the key still contains that same buzz-record ID. Timeout, manual cancellation, and failed winner persistence all use this conditional cleanup so stale work cannot delete a replacement winner's lock.

Before arbitrating a new buzz, the controller opportunistically asks the deadline service to expire an overdue winner for that question and round. This keeps a valid post-deadline buzz from waiting solely for the next background polling interval while preserving PostgreSQL as the authority.

Alternative considered: rely on Redis key expiration notifications. Those notifications are not durable, can be disabled or lost, and cannot atomically check the PostgreSQL submission, so they are not sufficient.

### 3. Serialize winner submission and expiration on the buzz row

Create a shared buzz-deadline service used by both submission controllers. For a `BUZZ_OPEN` submission it opens a transaction, selects the active successful winner for the question and round `FOR UPDATE`, verifies requester ownership and the stored deadline, checks duplicate submission state, and inserts the answer through the same connection. If the deadline is already reached, the transaction marks the winner `TIMED_OUT` instead of inserting.

The background sweeper selects due active rows with `FOR UPDATE SKIP LOCKED`, rechecks for a committed matching submission after obtaining the lock, and only then marks the row inactive. Both paths therefore contend on the same row. The transaction winner determines the one valid outcome; database uniqueness remains a secondary guard.

Alternative considered: check the deadline in each controller and call the existing repository methods. That leaves a time-of-check/time-of-use gap in which a sweep can release the winner before the separately connected insert.

### 4. Use a daemon background service with synchronous startup recovery

A single-thread scheduled service performs an immediate synchronous sweep during API startup before HTTP traffic is accepted, then runs fixed-delay sweeps at a short interval. The SQL is idempotent and uses row locking, so repeated sweeps and multiple API processes cannot transition the same record twice. The API owns and closes the scheduler during shutdown; the worker catches and reports an individual sweep failure without terminating future runs.

Alternative considered: create one process-local scheduled future per buzz. Per-buzz futures are lost on restart, are harder to cancel across lifecycle actions, and do not coordinate across API instances.

### 5. Reuse `BUZZ_CANCELLED` with an explicit timeout reason

After a timeout transaction commits, conditionally delete the matching Redis winner key by buzz-record ID and publish `BUZZ_CANCELLED` with `packageId`, `questionId`, `roundId`, expired user identity, and `reason: TIMEOUT`. Existing student and teacher reducers already clear a matching cancellation while leaving `BUZZ_OPEN`; the student page adds only a timeout-specific notice. Manual cancellation continues to use the same event without the timeout reason.

A new event type was considered, but it would duplicate the exact state transition and require parallel reducer logic. The reason field preserves the audit and UX distinction without changing release semantics.

### 6. Validate new limits while preserving legacy compatibility

Teacher package authoring gets a whole-number seconds input initialized to 30 and sends that value instead of a constant. Create/update APIs reject explicitly supplied invalid limits. Existing stored null or non-positive legacy values produce a null winner deadline and retain manual-release behavior, avoiding a destructive migration.

### 7. Render a winner-only countdown from the absolute server deadline

Expose the active winner's stored `answerDeadlineAt` through buzz success/realtime payloads and current-question reconciliation. The student client displays a countdown only when the current user is the active winner, calculating remaining whole seconds from that absolute timestamp rather than decrementing a locally owned duration. This lets refresh and reconnect recover the same remaining time and prevents timer drift from extending the lock.

Reaching zero changes only the countdown presentation. PostgreSQL timeout arbitration remains authoritative; once the client receives the matching current-round `BUZZ_CANCELLED` event with `reason: TIMEOUT`, it clears the winner and draft and returns the question to the available-to-buzz state for every student. Submission, manual cancellation, reset, a new round, and authoritative question reconciliation also clear or replace the countdown.

## Risks / Trade-offs

- [A sweep runs slightly after the exact deadline] → Reject late submissions synchronously from the stored deadline and sweep frequently; correctness does not depend on UI timing.
- [Redis deletion fails after the database commits expiration] → Keep the database winner check authoritative, make every cleanup compare the stored buzz-record ID, retry cleanup on subsequent reconciliation, and use a TTL longer than the deadline as a final bound.
- [A process crashes after database expiration but before event publication] → Startup/reconnect answer and question reconciliation restores authoritative state; the event remains best-effort like existing realtime delivery.
- [Existing active winners have no stored deadline during rollout] → Leave them under existing manual cancellation/reset semantics rather than inventing a partial elapsed window.
- [Polling adds database load] → Use a partial index on active rows with non-null deadlines, bounded batches, and `SKIP LOCKED`.
- [Timeout retains a previously awarded buzz bonus] → Preserve current scoring semantics in this change and document reversal as a separate product decision if desired.

## Migration Plan

1. Add the nullable deadline column and partial due-winner index idempotently.
2. Deploy atomic winner-submission persistence and limit validation before enabling the worker.
3. Start synchronous overdue recovery followed by scheduled sweeps and realtime release publication.
4. Deploy the teacher N input and timeout notice, then verify submit/timeout races, restart recovery, manual cancellation, stop/reset, and same-round rebuzz.
5. Monitor `TIMED_OUT` history counts, sweep failures, late-submission rejections, and Redis cleanup errors.

Rollback stops the worker and returns submission routing to the prior methods. The nullable deadline column and `TIMED_OUT` historical records remain safe to retain; rollback must not reactivate already expired winners.

## Open Questions

None. This change uses the existing per-question/package answer limit, keeps timeout in the same round, and intentionally leaves buzz-bonus reversal unchanged.
