## Context

The system enforces a fixed limit of one completed buzz answer for each positive group number across all classrooms during an Asia/Taipei calendar day. Usage is stored in `global_group_buzz_counts` under the composite key `(group_number, success_date)`. Teacher member payloads expose the current global count as `groupSuccessfulBuzzCount`, and the dashboard renders it as `今日成功搶答：count / 1`.

Question reset and answer-limit reset have deliberately separate meanings. Question reset changes round lifecycle state. Answer-limit reset changes only the dated global counters and must not rewrite the records that originally consumed those counters.

## Goals / Non-Goals

**Goals:**

- Let an authenticated teacher or assistant restore eligibility for every group during the current Taipei day.
- Make the system-wide scope explicit through confirmation before mutation.
- Preserve all question state and historical data.
- Refresh visible teacher-side usage immediately after a successful reset.

**Non-Goals:**

- Resetting only the active classroom or selected group numbers.
- Deleting or modifying submissions, scores, buzz attempts, Sheets data, or prior-day counters.
- Changing the fixed limit of one or changing the automatic Taipei-midnight behavior.
- Reconstructing counters from retained submissions after a manual reset.

## Decisions

### Delete only today's dated counter rows

The repository operation deletes rows from `global_group_buzz_counts` where `success_date = (CURRENT_TIMESTAMP AT TIME ZONE 'Asia/Taipei')::date` and returns the affected-row count. Absence of a row already represents zero usage, so deletion restores eligibility without introducing a second source of truth or a special reset marker.

Historical submissions remain unchanged. Consequently, a group may legitimately have multiple successful submissions on one date after an authorized reset. The dated counter continues from zero and the next accepted answer consumes the newly restored slot.

Alternative considered: update today's rows to zero. Deletion better matches the existing representation for unused groups and avoids retaining redundant zero-count rows.

### Provide a dedicated authorized operation

Expose a dedicated teacher API operation instead of extending question batch actions. The controller requires an authenticated teacher or assistant. The reset is system-wide by design because group limits are global across classrooms; the active classroom code is used for authorization/context only and must not narrow the deleted group set.

The response reports a stable success status and the number of counter rows removed. A zero-row result is successful and means no group currently had recorded usage.

### Keep the two reset controls visually adjacent but behaviorally independent

Render `重置答題限制` immediately beside `重置全部題目` in the dashboard batch-action row. It uses its own confirmation text exactly as specified and calls only the answer-limit reset API. It does not invoke `RESET_ALL` or mutate local question state.

After success, reload classroom members through the existing member endpoint. This updates every visible `groupSuccessfulBuzzCount` to zero without optimistic assumptions and preserves server authority. Display a success notice that distinguishes an effective reset from the already-empty case.

### Serialize naturally with answer commits

The reset delete and submission counter upsert remain ordinary database transactions. PostgreSQL row/index locking determines ordering when they overlap: an answer committed before the reset is cleared; an answer committed after the reset consumes the restored slot. The API does not promise to invalidate an answer that is concurrently committed after the reset completes.

## Risks / Trade-offs

- Because counters are global, one teacher can restore eligibility for groups active in other classrooms. The confirmation explicitly states all groups, and authorization prevents student access.
- Retained submissions no longer derive the current counter after a reset. This is intentional: the counter represents eligibility since the latest reset or Taipei midnight, while submissions remain immutable history.
- A concurrent post-reset submission may make one group display `1 / 1` immediately. Reloading from the server makes that state accurate rather than forcing a local zero.

## Migration Plan

No database schema migration is required. Deploy the API operation before or together with the web client. Rollback removes the UI and endpoint; existing counters and historical records remain valid.

