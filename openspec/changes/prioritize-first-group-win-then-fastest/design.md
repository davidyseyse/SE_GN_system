## Context

Buzz receipt order is scoped to a question and round. Students prepare and submit answers after their attempts are recorded, and the teacher later finalizes each round. Finalization currently scans pending candidates by receipt order and conditionally increments `global_group_buzz_counts` only when the dated count is below the fixed limit of one. Candidates from groups already at one are marked `SUCCESS_LIMIT_REACHED`, which can leave later questions without a winner or Sheets value.

The dated counter and reset operation are still useful, but the counter must become a ranking input rather than an eligibility gate. Cross-question finalizations may execute concurrently, so reading counts, choosing a priority tier, and incrementing the winner must behave as one serialized policy decision.

## Goals / Non-Goals

**Goals:**

- Give submitted candidates from groups with zero current-day successes priority over previously successful groups.
- Use server receipt order as the tie-breaker within the selected priority tier.
- Fall back to the fastest submitted candidate when no zero-success candidate exists for that question.
- Permit success counts above one and synchronize every finalized winner to Google Sheets.
- Preserve daily rollover, explicit reset, one winner per question round, idempotent retry, and historical data.
- Make concurrent cross-question finalization observe a coherent order.

**Non-Goals:**

- Guarantee an equal final distribution across groups.
- Batch-optimize winners after all questions close.
- Compare receipt sequences across different questions.
- Guarantee an answer when nobody submits one.
- Reorder already finalized historical winners.

## Decisions

### Rank by a zero-success tier and then receipt order

Within one question and round, load all active attempts having a pending submission together with each candidate group's current Taipei-day count. Sort candidates lexicographically by:

1. zero-success tier (`0` when the current count is zero, otherwise `1`),
2. server receipt order,
3. received timestamp,
4. stable record identifier.

The first candidate becomes the winner. This is equivalent to filtering to zero-success groups when any are present and otherwise selecting the normal fastest candidate. It also handles absent or non-participating groups naturally because only candidates for the current question affect the decision.

Alternative considered: wait until every registered group has succeeded once before enabling fastest fallback. An absent group could prevent the transition indefinitely and cause avoidable blank answers.

### Serialize finalization policy decisions by Taipei date

Acquire a PostgreSQL transaction-scoped advisory lock derived from the current Asia/Taipei date before loading candidate counts. Hold it through winner marking and the unbounded counter upsert. Question-round row locking and the existing partial unique winner invariant continue to protect idempotency for the same round.

This creates a total commit order for cross-question first-win decisions. It avoids two concurrent questions both observing group 1 at zero and granting it first-win priority. Classroom buzz finalization volume is small enough that the short critical section is acceptable; Google Sheets synchronization remains outside the transaction and therefore outside the advisory lock.

Alternative considered: lock only candidate group counter rows. Missing rows for zero-count groups and overlapping candidate sets make lock acquisition and ordering more complex, while independently locked sets can still produce policy decisions from inconsistent snapshots.

### Increment counters without a maximum

Replace the conditional global-counter upsert with an atomic unbounded increment keyed by `(group_number, success_date)`. Existing counts require no migration. The teacher reset continues deleting current-date rows, and the date-key behavior continues providing automatic Taipei-midnight rollover.

Buzz registration and pending-answer persistence do not reject a group because of its current count. Only finalization chooses a winner and increments the count.

### Remove hard-limit semantics from contracts and UI

Retain `groupSuccessfulBuzzCount` as the accumulated current-day total. Remove or deprecate `successfulBuzzLimit`, because reporting `1` would incorrectly imply rejection and reporting an arbitrary unlimited sentinel would leak repository implementation details. Teacher member rows display `今日成功搶答：N 次`.

The student client removes `SUCCESS_LIMIT_REACHED` handling. Other unsuccessful outcomes continue using neutral feedback. Realtime winner and review events retain the winning group and updated count where already supported.

### Preserve one Sheets write per finalized round

After the database transaction commits, use the existing winner-specific formatting and Sheets synchronization path. A fallback winner is indistinguishable from a first-priority winner for formatting and retry purposes. No candidate produces a Sheets write unless selected as the persisted round winner.

## Risks / Trade-offs

- [Fastest group can win every remaining question after all submitted candidates have prior successes] → This is the chosen scheme: first-win opportunity is protected, then speed decides without a hard cap.
- [Finalization order affects which questions consume first-win priority] → Document that counts are evaluated at serialized finalization time; do not imply question-number ordering unless the teacher finalizes in that order.
- [A global date-scoped advisory lock serializes otherwise independent classrooms] → Keep only database selection and counter mutation inside the critical section; perform realtime and Sheets work after commit.
- [A zero-success group submits slowly and beats a faster previously successful group] → Make this priority visible to teachers as the intended first-win rule.
- [Existing clients expect `successfulBuzzLimit`] → Coordinate API and web deployment, or retain the field temporarily as deprecated while removing all behavioral reliance on it.

## Migration Plan

1. Deploy API support for unbounded dated counter increments and serialized priority selection.
2. Remove early hard-limit rejection and obsolete `SUCCESS_LIMIT_REACHED` branches.
3. Update API contracts and teacher/student displays to accumulated-count semantics.
4. Deploy web changes together with or after the compatible API response.
5. Validate ordered six-question behavior, absent-group fallback, reset/rollover, and concurrent finalization.

Rollback restores the hard limit of one using the retained dated counters. Counts greater than one must be treated as already at the restored limit; no historical submission or Sheets value is deleted.

## Open Questions

None. Priority is scoped to the global current-day group count already used by the system, and cross-question allocation follows serialized finalization order.
