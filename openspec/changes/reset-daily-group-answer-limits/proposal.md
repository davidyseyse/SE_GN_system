## Why

The fixed daily global answer limit prevents every group number from completing more than one successful buzz answer per Asia/Taipei calendar day. During classroom operation, teachers currently have no way to restore eligibility after a rehearsal, mistaken submission, or intentional restart; they must wait until the next Taipei day.

## What Changes

- Add a teacher-dashboard button named `重置答題限制` beside `重置全部題目`.
- Require confirmation with `確定要重置所有組別的答題限制嗎？` before performing the action.
- Add an authorized API operation that resets today's global successful-answer count for every group number.
- Refresh the teacher member view after success so every displayed current-day group usage becomes `0 / 1`.
- Preserve questions, rounds, attempts, submissions, scores, historical records, and Google Sheets content.
- Keep the fixed daily limit at one; the operation restores today's eligibility instead of changing the configured limit.

## Capabilities

### New Capabilities

- `daily-group-answer-limit-reset`: Authorized system-wide reset of all groups' current-day answer-limit usage and its teacher-dashboard control.

### Modified Capabilities

- `student-buzz-success-limit`: Current-day global success accounting can be explicitly reset by an authorized teacher or assistant without changing historical submissions.

## Impact

- Teacher web dashboard batch-action controls and member-count refresh behavior.
- Web API client types and request function.
- API authorization/controller, service, and repository behavior for current-day global group counters.
- Tests for authorization, date scoping, data preservation, response behavior, confirmation text, button placement, and refreshed counts.

