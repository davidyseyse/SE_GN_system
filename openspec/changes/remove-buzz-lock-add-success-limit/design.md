## Context

The current API uses Redis `SET NX` as a round winner lock. The first buzz request becomes `successful`, later requests become `LATE`, the client disables every other buzz button, and `BuzzDeadlineService` authorizes only the lock holder's submission. This provides exclusivity but prevents the requested open participation model.

Questions already have round identifiers, Redis receipt sequences, persisted buzz records, per-attempt timestamps, submission-to-round relationships, realtime invalidation, legacy answer-deadline fields, and idempotent Sheets overwrite behavior. The redesign retains the useful boundaries while moving winner selection to answer commit time and stops enforcing individual answer deadlines.

## Goals / Non-Goals

**Goals:**

- Accept and order every eligible student's buzz attempt without a winner lock.
- Select the fastest active attempt by server-generated receipt order.
- Persist and synchronize only one authoritative winner answer per round.
- Enforce one completed win per group number per Asia/Taipei calendar day globally across all classrooms.
- Preserve scalar/package Sheets formatting, retries, rounds, realtime updates, and teacher correction.
- Keep recorded attempts active without an individual countdown until submission, cancellation, or round lifecycle change.

**Non-Goals:**

- Sending every student's answer to Google Sheets.
- Ranking by client timestamps, network latency estimates, or browser clocks.
- Retrofactively limiting legacy classrooms that omit the new setting.
- Removing teacher lifecycle states such as `LOCKED`; the removed lock is the student winner lock, not the teacher's stopped-question state.

## Decisions

### Record attempts first and select the winner when an answer commits

Every under-limit buzz request obtains a Redis `INCR` receipt order and is persisted as `RECORDED`, active, and not yet successful. Redis is used only as a monotonic round sequence; no `SET NX`, winner key, or lock-release operation remains.

At submission time, the API queries the lowest-order active attempt for the current round. If it belongs to another student, the request returns `NOT_FASTEST` without writes. If it belongs to the requester, the API attempts the atomic winner commit.

Why: this lets all students buzz and prepare an answer while preserving an unambiguous server-defined fastest student. Selecting at answer commit also means `successful` has the same meaning as the new count: a fastest buzz followed by a persisted answer.

Alternative considered: mark the first buzz request as winner immediately without Redis locking. Concurrent database visibility could select a later committed request even when it had a larger receipt order, and it would count a success before an answer existed.

### Use database invariants for one winner and atomic success accounting

Add `global_group_buzz_counts(group_number, successful_buzz_count)` and `submissions.buzz_winner BOOLEAN NOT NULL DEFAULT FALSE`. A partial unique index on `(question_id, round_number)` where `buzz_winner = TRUE` guarantees one committed buzz winner per round.

The winner transaction conditionally upserts the global group counter only while its count is below the fixed limit of one, inserts the winner submission, and marks the matching buzz attempt successful. Any failed insert or concurrent loser rolls back the counter. The Sheets request occurs only after commit and therefore never participates in the database transaction.

Why: a conditional update and unique index provide concurrency safety without an application-level winner lock. The persisted counter makes limit checks inexpensive and observable.

Alternative considered: derive the count from historical buzz records on every request. Concurrent questions could both observe the same pre-submit count and exceed the limit.

### Use a fixed daily global limit

The effective `successfulBuzzLimit` is always `1`; participation JSON does not control it. The teacher UI does not expose the setting. Global counters use `(group_number, success_date)` where `success_date` is derived in PostgreSQL using the `Asia/Taipei` timezone. Existing counters are migrated to the Taipei date of their last update, and winner submissions are backfilled by group number and Taipei submission date.

At buzz time, a student whose group is already at today's limit receives `SUCCESS_LIMIT_REACHED` before receipt sequencing or persistence. Submission rechecks today's group limit transactionally because another student in the same group may have committed after the buzz. At Taipei midnight a new date key is used automatically, so no scheduled reset or destructive cleanup is required.

### Do not enforce individual answer deadlines

New buzz records leave the legacy `answer_deadline_at` column null. Winner selection ignores that column, no background deadline sweep runs, and the student interface exposes no countdown. Cancellation still locates and inactivates only the caller's current attempt.

Why: the classroom does not require a per-student answer-time restriction. An attempt advances to the next receipt order only when the earlier student explicitly cancels or the teacher changes the round lifecycle.

### Preserve response compatibility while separating recording from winning

The buzz response keeps `accepted` but defines it as "attempt recorded" and adds `successful`, `outcomeStatus`, `receiptOrder`, `groupNumber`, `groupSuccessfulBuzzCount`, and `successfulBuzzLimit`. The student client opens an answer editor for its own recorded attempt and keeps other students' buzz buttons enabled.

Submission responses add `accepted` and `outcomeStatus`. Only `accepted: true` responses include a persisted submission ID and proceed to score/event/Sheets side effects. Skipped responses use `NOT_FASTEST`, `SUCCESS_LIMIT_REACHED`, `NO_ACTIVE_ATTEMPT`, or `WINNER_ALREADY_SUBMITTED`.

The student UI intentionally maps skipped buzz and submission outcomes to one neutral unsuccessful-buzz message. It does not display whether the underlying reason was the global group limit, receipt order, or an already committed winner. The API retains its distinct outcome for enforcement, tests, and authorized teacher-side observability.

### Prepare answers before the buzz action

During `BUZZ_OPEN`, the student client renders every answer field immediately. It enables the buzz button only after all package items contain non-whitespace answers. A click first calls the existing buzz endpoint to obtain the authoritative receipt order, then immediately calls the existing answer endpoint with the prepared values. If registration fails, the answer endpoint is not called; if the second request fails, the client cancels the recorded attempt so it cannot indefinitely block later students.

This keeps server ordering and transactional winner persistence unchanged while matching the classroom interaction: decide answers first, then compete on the final buzz action.

### Split login routes by audience

The combined role-selecting login page is replaced by `/teacher/login` and `/student/login`. Role guards redirect to their matching login route. Each login form omits links to the other audience, and application navigation shows only the login action matching the current teacher or student route.

### Manage teacher-owned classrooms explicitly

The API lists only classrooms created by the authenticated teacher or assistant and permits deletion only when that actor owns the classroom. The dashboard replaces free-text resume-code entry with this owned-classroom selector. The selector starts blank, so loading or resuming does not silently choose a classroom; successful creation adds and selects the new classroom. Deletion requires browser confirmation and removes the selected classroom and its cascaded classroom data.

### Let students select an available classroom

The API exposes a read-only list containing only the identifier, name, code, and status of existing classrooms. The student login loads this list without requiring a student token, starts with no classroom selected, and prevents joining until the student explicitly chooses one. Joining and membership validation continue through the authenticated join endpoint after student login.

### Compute group scores from the linked Google Sheet on demand

The teacher dashboard provides a score-statistics component for a selected Sheet configuration. When the teacher activates the statistics action, an authenticated teacher-only API reads the populated cell display values from every worksheet in that spreadsheet and counts each answer suffix matching `_G<number>`. Results are sorted by numeric group number and include the number of matching successful-answer cells per group, the distinct successful-group count, and the total marker count.

The spreadsheet remains the source of truth for this report; the API does not persist a duplicate score table. Reading occurs only on demand so teachers see the sheet's current contents, including any authorized corrections made directly in Google Sheets. Values without a positive numeric group suffix are ignored.

## Risks / Trade-offs

- [Students may spend time answering although a faster attempt exists] → Keep the UI clear that only a successful buzz is recorded while using a neutral skipped message that does not disclose group-limit status.
- [Redis sequence increment succeeds but database persistence fails] → Gaps are allowed; ordering depends on relative values, not contiguity.
- [A faster attempt never submits] → The faster student can cancel, or the teacher can reset/close the round; without an individual time limit the system intentionally does not auto-advance.
- [Sheets fails after the winner transaction] → Preserve the existing `RETRYABLE` status and idempotent retry without changing the success count.
- [Legacy winner rows lack the new submission flag] → Backfill `buzz_winner` from successful buzz records before creating the partial unique index.
- [Classroom deletion removes questions and submissions] → Require an explicit confirmation and enforce creator ownership in the API.

## Migration Plan

1. Add the member success counter and submission winner marker with non-breaking defaults.
2. Backfill winner markers and dated global group counts from successful buzz records that have matching round submissions.
3. Create the partial one-winner-per-round index and supporting attempt lookup indexes.
4. Deploy API behavior that no longer reads or writes Redis winner keys.
5. Deploy the updated teacher setting and student open-buzz interface.
6. Update validation/load tests and monitor skipped-outcome rates and Sheets retry counts.

Rollback restores winner-lock behavior while retaining additive columns. The counter and winner marker remain for auditability; no accepted submission or historical buzz record is deleted.

## Open Questions

None. The implementation always uses a hidden daily global group limit of one, with days defined by Asia/Taipei time.
