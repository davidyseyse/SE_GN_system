import test from "node:test";
import assert from "node:assert/strict";
import {
  buzzInteractionKey,
  canCancelBuzz,
  mergeSubmittedInteractionKeys,
  ownSubmittedInteractionKeys,
  realtimeEventApplies,
  removeInteractionEntry,
  removeQuestionRoundKeys
} from "../dist/student/buzzRoundState.js";

test("tracks interaction state by question and round", () => {
  assert.equal(buzzInteractionKey("q1", "2"), "q1:2");
  assert.notEqual(buzzInteractionKey("q1", "1"), buzzInteractionKey("q1", "2"));
});

test("ignores stale winner and cancellation events", () => {
  assert.equal(realtimeEventApplies("BUZZ_RESULT", "1", "2"), false);
  assert.equal(realtimeEventApplies("BUZZ_CANCELLED", "1", "2"), false);
  assert.equal(realtimeEventApplies("BUZZ_RESULT", "2", "2"), true);
  assert.equal(realtimeEventApplies("BUZZ_RESET", "1", "2"), true);
});

test("reconciles a current-round explicit cancellation", () => {
  assert.equal(realtimeEventApplies("BUZZ_CANCELLED", "2", "2"), true);
  assert.deepEqual(
    removeInteractionEntry({ "q1:2": "current draft", "q1:1": "old draft", "q2:2": "other draft" }, "q1:2"),
    { "q1:1": "old draft", "q2:2": "other draft" }
  );
});

test("rejects a stale cancellation", () => {
  const drafts = { "q1:2": "current draft" };
  const reconciled = realtimeEventApplies("BUZZ_CANCELLED", "1", "2")
    ? removeInteractionEntry(drafts, "q1:1")
    : drafts;
  assert.equal(reconciled, drafts);
});

test("shows cancel only for an active unsubmitted attempt", () => {
  assert.equal(canCancelBuzz(true, false), true);
  assert.equal(canCancelBuzz(true, true), false);
  assert.equal(canCancelBuzz(false, false), false);
});

test("reset clears every local round for one question only", () => {
  assert.deepEqual(
    removeQuestionRoundKeys(["q1:1", "q1:2", "q2:1"], "q1"),
    ["q2:1"]
  );
});

test("restores submitted keys from requester-relative ownership", () => {
  assert.deepEqual(
    ownSubmittedInteractionKeys(
      [{ id: "q1", roundId: "2" }, { id: "q2", roundId: "4" }],
      {
        q1: [
          { isOwnSubmission: false },
          { isOwnSubmission: true }
        ],
        q2: [{ isOwnSubmission: false }]
      }
    ),
    ["q1:2"]
  );
});

test("a stale refresh cannot clear a locally confirmed submission", () => {
  const submitted = mergeSubmittedInteractionKeys(["q1:2"], []);
  assert.deepEqual(submitted, ["q1:2"]);
  assert.equal(canCancelBuzz(true, submitted.includes("q1:2")), false);
});

test("reload recovers own submission without affecting another question", () => {
  const submitted = ownSubmittedInteractionKeys(
    [{ id: "q1", roundId: "2" }, { id: "q2", roundId: "2" }],
    {
      q1: [{ isOwnSubmission: true }],
      q2: [{ isOwnSubmission: false }]
    }
  );

  assert.deepEqual(submitted, ["q1:2"]);
  assert.equal(canCancelBuzz(true, submitted.includes("q1:2")), false);
  assert.equal(canCancelBuzz(true, submitted.includes("q2:2")), true);
});

test("an old round submission does not submit a new round", () => {
  const afterReset = removeQuestionRoundKeys(["q1:2", "q2:1"], "q1");
  const newRound = mergeSubmittedInteractionKeys(afterReset, []);

  assert.deepEqual(newRound, ["q2:1"]);
  assert.equal(newRound.includes("q1:3"), false);
});
