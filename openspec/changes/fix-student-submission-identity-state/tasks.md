## 1. Submission ownership API

- [x] 1.1 Extend the current-round submitted-answer response with an `isOwnSubmission` boolean derived from the authenticated requester's internal user identity.
- [x] 1.2 Update the web `SubmittedAnswerView` contract for the additive ownership field.
- [x] 1.3 Add API coverage proving the requester’s submission is marked as owned while another student’s submission is not.

## 2. Student submitted-state recovery

- [x] 2.1 Reconstruct submitted interaction keys from `isOwnSubmission` instead of comparing the response UUID with the session's external login ID.
- [x] 2.2 Merge authoritative submitted keys into locally confirmed state so an older refresh cannot clear a successful submission in the same question and round.
- [x] 2.3 Preserve authoritative reset cleanup so ended-round keys are removed without affecting other questions or new rounds.

## 3. UI regression coverage

- [x] 3.1 Add student state tests for ownership-based recovery and monotonic submitted-key merging.
- [x] 3.2 Add a post-submit refresh regression test proving the submit and cancel-lock actions remain unavailable.
- [x] 3.3 Add reload/reconnect, multi-question, and new-round regression cases.

## 4. Verification

- [x] 4.1 Run the API test suite and validation scenarios covering submission and cancellation.
- [x] 4.2 Run the web unit tests and production build.
- [x] 4.3 Manually verify submit-success, realtime refresh, browser reload, and teacher reset/reopen behavior with two student identities.
