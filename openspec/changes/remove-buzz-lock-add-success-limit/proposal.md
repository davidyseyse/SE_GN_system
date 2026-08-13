## Why

The current buzz flow grants an exclusive Redis lock to the first request, which disables every other student's buzz action and couples answer eligibility to a single lock holder. Classrooms need every student to be able to register a buzz attempt while still selecting the server-observed fastest eligible student for the authoritative answer and Google Sheets synchronization.

The system also needs a fixed daily global cap so the same numbered group cannot win again in another classroom on the same day. The cap is one completed buzz win per group per Asia/Taipei calendar day across the whole system, must be enforced when an answer is committed, and over-limit attempts must not create submissions or update Google Sheets.

## What Changes

- **BREAKING** Remove the exclusive Redis winner-lock contract from buzz handling; eligible buzz attempts remain available to every student throughout an open round.
- Record every eligible attempt with a server receipt order and designate the lowest-order attempt as the round winner without using a winner lock.
- Keep answer and Google Sheets writes restricted to the fastest eligible student for the round.
- Show answer fields as soon as buzzing opens, require complete answers before enabling the buzz action, and submit the prepared answers immediately after recording the buzz.
- Enforce a fixed system-wide daily limit of one completed buzz win for each positive group number across all classrooms, resetting at Asia/Taipei midnight.
- Count a success only after the winner's answer is persisted, and atomically refuse a submission that would exceed the fixed limit.
- Do not impose an individual answer deadline after a student buzzes; an attempt remains eligible until submission, student cancellation, or teacher round reset/closure.
- Skip persistence and Sheets synchronization for groups that have reached the limit, expose a distinct API outcome, and mask the specific limit reason from students behind a neutral unsuccessful-buzz message.
- Keep the teacher classroom-creation controls aligned in a responsive grid instead of allowing full-width inputs to wrap unpredictably.
- Let teachers select their own existing classrooms from a dropdown, automatically select only a newly created classroom, and delete a selected classroom.
- Let students choose an existing classroom from a dropdown instead of manually entering a classroom code.
- Keep each audience on its own login surface by removing cross-login links and showing only the matching login action in the application header.
- Update realtime state, student controls, concurrency tests, validation guidance, and user-facing documentation.
- Add an on-demand teacher score-statistics component that reads the selected linked Google Sheet and counts successful `_G<number>` answer markers by group.

## Capabilities

### New Capabilities

- `open-buzz-ranking`: Non-exclusive buzz registration, server-side receipt ordering, fastest-student answer eligibility, and Sheets synchronization behavior.
- `student-buzz-success-limit`: Global per-group completed-win limit, atomic success accounting, skip behavior, and visibility.

### Modified Capabilities

None. This repository has no promoted main capability specs; the new delta specs replace the superseded lock-based behavior documented by historical changes.

## Impact

- API buzz, submission, attempt lifecycle, repository, and classroom policy code.
- PostgreSQL global-group success counters and migration/backfill logic.
- Redis usage changes from winner locking to receipt sequencing only.
- Buzz/submission API payloads and realtime event handling.
- Teacher classroom creation controls and student buzz/answer interaction state.
- Separate teacher and student login routes and forms.
- API concurrency/validation scripts, web tests, OpenSpec artifacts, and operational/user documentation.
- Google Sheets read integration and teacher-side group score statistics.
