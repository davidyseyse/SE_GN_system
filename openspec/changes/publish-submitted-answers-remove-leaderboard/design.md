## Context

Answers are already stored in PostgreSQL and optionally synchronized to Google Sheets. A successful submission publishes `SUBMISSION_RECEIVED`, but its payload contains identity and review state rather than the answer. The teacher client can fetch answers through a teacher-only report endpoint, but only after a question is selected for review. The student client has no answer-read endpoint and clears its local answer text after a successful submission.

The realtime broker is process-local, so event replay alone cannot recover answers after a server restart or missed event. Existing submission uniqueness and reset behavior are round-scoped. The internal round must remain authoritative even though users do not want round numbers displayed.

Leaderboard rendering reads `score_records`, computes rankings, and writes a snapshot on each leaderboard read. Scoring writes happen independently in submission, review, buzz, and deletion flows.

## Goals / Non-Goals

**Goals:**

- Make each successful submission visible automatically and immediately to all authenticated members of that classroom.
- Restore the same answers after refresh, reconnect, or event loss.
- Prevent client double-click submission while retaining server duplicate protection.
- Keep current-round answers separate from historical answers without exposing round numbers.
- Remove leaderboard-specific presentation and backend dependencies without changing scoring.
- Preserve current Google Sheets, review, buzz, reset, and reporting behavior unless explicitly affected.

**Non-Goals:**

- Adding a manual answer-loading button.
- Displaying submission timestamps or round numbers.
- Allowing students to modify, delete, or resubmit an answer in the same round.
- Removing scoring, score detail, or score exports.
- Changing `OPEN_ALL`, `STOP_ALL`, or `RESET_ALL` semantics.

## Decisions

### 1. Use an authoritative classroom-member answer endpoint

Add a classroom-scoped query accepting a classroom code and question identifier. The server resolves the question within the classroom, verifies that the authenticated user is an active classroom member, derives the question's current round, and returns only submissions from that round.

The response includes submission ID, question ID, user ID, display identity, answer text, and review status where needed by the teacher. It does not expose submission time or round number to the UI contract.

The existing teacher reporting endpoint remains an administrative/history surface if still needed; it is not reused as the student endpoint because its authorization and historical purpose differ.

### 2. Realtime events invalidate; REST reads reconcile

Create/update/delete/review operations publish a submission-change event containing identifiers needed to invalidate one question. Connected teacher and student clients immediately refetch that question's current answers. Initial entry, refresh, reconnect, and resync also fetch authoritative answers for visible questions.

Full answer text is not required in the WebSocket event. This keeps authorization centralized in the read endpoint and makes missed-event recovery identical to normal loading.

### 3. Intentionally publish answers to all current classroom members

Answers become visible as soon as persistence succeeds, including while the question remains open. This is an explicit product policy, not an accidental consequence of question publication state. Membership verification prevents users outside the classroom from querying the answers.

### 4. Keep round separation internal

The backend filters answers by the current `question_buzz_rounds.current_round`. Clients associate returned answers with a question but render no round label. Reset clears the ended round from the current UI; reopening advances the round and presents an empty current answer list until a new submission succeeds. Historical rows remain available to administrative reporting.

### 5. Model submission in flight per question and round

Before starting the request, the student client marks the question/round interaction key as submitting. The existing button text remains unchanged, but the button is disabled. Success changes the interaction to submitted and leaves the button disabled. Failure clears only the submitting flag, preserves the draft, shows the error, and allows retry.

The backend unique constraint and duplicate check remain the final concurrency boundary.

### 6. Render answers inline and simplify visible status

Teacher and student question cards render the current answer list automatically. Each row shows the existing student display identity and answer text. Submission time and round are omitted. The visible success status is exactly `✓ 已作答`; internal `PENDING_REVIEW` and review data remain unchanged for teacher workflows and scoring.

### 7. Remove ranking, retain scoring

Remove leaderboard panels, client state/queries, visibility selection, `/api/leaderboard`, ranking calculation, snapshot writes, leaderboard-specific types/events, and obsolete validation/documentation. Preserve `score_records`, score mutations, `/api/scores/detail`, score exports, and scoring policies.

Replace `LEADERBOARD_UPDATED` with a neutral submission/review or score update event only where another retained UI needs invalidation. No scoring transaction depends on successful realtime delivery.

Database cleanup is staged: first stop all reads/writes, then drop `leaderboard_snapshots` and its index. Remove `classrooms.leaderboard_visibility` only after classroom creation and stored-data compatibility are migrated.

### 8. Rename batch controls without semantic changes

Only the visible labels change:

- `一鍵開放全部搶答` becomes `開放全部搶答`
- `一鍵停止全部搶答` becomes `停止全部搶答`
- `一鍵重置全部題目` becomes `重置全部題目`

The commands, confirmations, eligibility rules, and result summaries remain unchanged.

## Risks / Trade-offs

- [Immediate public answers can influence students who have not answered] — Treat this as the explicit requested classroom policy and cover it in acceptance tests.
- [A classroom code alone could expose answers] — Require authenticated membership in the resolved classroom and verify that the question belongs to it.
- [An event can arrive before the write is visible or responses can arrive out of order] — Publish after persistence commits; reconcile from the API and ignore stale requests using question identity/current client lifecycle.
- [Reset can race with answer fetch] — Derive the current round server-side and refetch after reset so ended-round answers disappear from the active UI.
- [Removing leaderboard code could accidentally remove scoring] — Separate deletion tasks by dependency and assert score records, review scoring, deletion reversal, detail, and export behavior.
- [Existing teacher report returns historical submissions without round filtering] — Keep administrative history separate from the current classroom-member projection.

## Migration Plan

1. Add the membership-authorized current-answer projection and submission-change events without removing existing behavior.
2. Add teacher and student automatic loading, inline display, reconnect recovery, and per-interaction submit locking.
3. Validate create/update/delete/review propagation, refresh recovery, membership denial, reset/reopen separation, and submission retry.
4. Remove leaderboard UI, queries, visibility controls, route/service wiring, event usage, and snapshot writes.
5. Verify scoring, score detail/export, answer persistence, Sheets synchronization, buzz, and reset behavior.
6. Apply an idempotent database migration that drops leaderboard snapshot storage and, after compatibility checks, leaderboard visibility configuration.

Rollback can restore the leaderboard presentation while scoring remains continuously available. The new answer endpoint and additive events can remain deployed safely with older clients; database leaderboard columns/tables should only be dropped after the rollback window or recreated from migration SQL if rollback is required.

