## Context

See `proposal.md` for the motivation. The student session stores the public login identity returned by authentication, while the current-answer API exposes the submission table's internal user UUID as `userId`. The student page currently compares those values to reconstruct submitted state.

Submitting an answer also publishes a realtime event. The page reloads questions and current answers whenever the realtime event count changes, then replaces the complete local submitted-key collection with keys reconstructed from the response. This creates two related failure modes: mismatched identity namespaces never reconstruct the key, and an older in-flight reload can overwrite a locally confirmed submission.

The existing interaction key (`questionId:roundId`) is the correct scope for winner, draft, and submission UI state and should remain the state boundary.

## Goals / Non-Goals

**Goals:**

- Give the student client an explicit, authoritative way to recognize its own submissions.
- Make local submit success monotonic within a question and round until a round-reset transition occurs.
- Preserve current multi-question and round-scoped behavior.
- Keep the backend cancellation guard as the final security boundary.

**Non-Goals:**

- Changing user IDs stored in the database or authentication tokens.
- Exposing external student identifiers for every submission.
- Changing teacher answer views, review behavior, scoring, or Google Sheets synchronization.
- Allowing a submitted answer to be withdrawn.

## Decisions

### Return requester-relative ownership in the answer read model

The authenticated submissions endpoint will include an `isOwnSubmission` boolean for each returned answer. The server derives it by comparing the submission's internal `userId` with the authenticated requester's resolved internal user entity.

This is preferred over returning `externalUserId` because the UI only needs ownership, not another identifier. It avoids leaking additional identity data and prevents future clients from repeating cross-namespace comparisons. Retaining the existing internal `userId` field preserves consumers that use it for display grouping or teacher views.

Alternative considered: change `userId` to the external ID. This would silently alter the meaning of an existing field and could break teacher or other consumers.

### Treat successful local submission as monotonic within the interaction key

After the submit API succeeds, the client records the `questionId:roundId` key as submitted. Background answer refreshes merge authoritative own-submission keys into current local state rather than replacing the full set.

Keys are removed only through an authoritative round transition/reset cleanup. This protects against both identity/read-model delays and out-of-order refresh responses while preserving the existing round reset behavior.

Alternative considered: attach request sequence numbers and discard stale loads. Sequencing is useful for broad snapshot consistency but is more complex than necessary for the invariant here; monotonic merging directly expresses that a successful submission cannot become unsubmitted inside the same round.

### Keep cancel visibility derived from winner and submitted state

The existing rule `isWinner && !hasSubmitted` remains the rendering contract. The fix supplies reliable `hasSubmitted` state rather than adding event-specific button manipulation.

Alternative considered: clear winner state permanently after submit. Realtime-derived winner state can still identify the student as the winner, and other UI elements may need it; submission status is the correct fact for suppressing cancellation.

## Risks / Trade-offs

- [A consumer assumes answer objects have an exact field set] → Add the boolean as a backward-compatible response field and update typed clients.
- [Monotonic merging retains a key after a legitimate round reset] → Continue using question-and-round keys and explicitly remove all ended-round keys on authoritative reset.
- [A refresh fails after submission] → Local success still hides cancellation; a later successful refresh or reconnect recovers state from the server.
- [Backend and frontend ownership tests drift] → Add API contract tests and client state tests covering internal UUID versus external login ID.

## Migration Plan

1. Deploy the additive API response field and its contract test.
2. Update the web response type and submitted-state reconstruction/merge logic.
3. Run API, web unit, build, and manual realtime regression verification.

Rollback is safe because no database migration is required. The web change can be rolled back independently; the additive API field may remain without affecting older clients.
