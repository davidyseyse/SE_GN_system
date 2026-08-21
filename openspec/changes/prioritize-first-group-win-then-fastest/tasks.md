## 1. Winner policy and persistence

- [x] 1.1 Replace the conditional capped global-group counter update with an atomic unbounded current-Taipei-day increment while preserving reset and rollover behavior.
- [x] 1.2 Load each pending candidate's authoritative current-day group count and rank zero-success candidates ahead of previously successful candidates, using receipt order and existing stable tie-breakers within the chosen tier.
- [x] 1.3 Add a date-scoped transaction serialization boundary around candidate ranking, winner persistence, and counter increment while retaining same-round idempotency and one-winner enforcement.
- [x] 1.4 Remove buzz-registration and finalization rejection paths that use `SUCCESS_LIMIT_REACHED`; allow previously successful groups to remain valid candidates.

## 2. API, realtime, and Google Sheets behavior

- [x] 2.1 Update finalization responses and realtime payloads to report the winner's accumulated current-day group count without treating one as a maximum.
- [x] 2.2 Remove or compatibly deprecate `successfulBuzzLimit` from buzz, submission, finalization, member, and web-client contracts.
- [x] 2.3 Preserve exactly one winner and one winner-specific Sheets write per finalized round, including fastest-fallback winners and idempotent Sheets retries.
- [x] 2.4 Ensure a round with no valid submitted candidate still reports no eligible submission and performs no Sheets write.

## 3. User interfaces and documentation

- [x] 3.1 Change teacher member usage from `今日成功搶答：count / 1` to an accumulated `今日成功搶答：count 次` display.
- [x] 3.2 Remove obsolete student `SUCCESS_LIMIT_REACHED` handling while retaining neutral feedback for other unsuccessful outcomes.
- [x] 3.3 Update user cases, operational guidance, and reset descriptions from daily eligibility-limit semantics to daily first-win-priority accounting.

## 4. Verification

- [x] 4.1 Add service tests proving zero-success priority, fastest tie-breaking within that tier, and fastest fallback when no zero-success candidate submitted.
- [x] 4.2 Add the six-question scenario with submission order `1, 2, 3` for every question and ordered finalization, asserting winners `1, 2, 3, 1, 1, 1`, counts `4, 1, 1`, and six Sheets-bound winners.
- [x] 4.3 Add coverage for absent/non-participating groups, a sole previously successful candidate, reset, Taipei-day rollover, and no-submission rounds.
- [x] 4.4 Add concurrent cross-question finalization tests proving later decisions observe the earlier committed success count and no duplicate first-win decision is made from stale counts.
- [x] 4.5 Update web contract/UI tests, run API and web builds and test suites, and run strict OpenSpec validation.
