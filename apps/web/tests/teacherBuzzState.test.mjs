import test from "node:test";
import assert from "node:assert/strict";
import { applyTeacherBuzzEvent } from "../dist/teacher/teacherBuzzState.js";

const question = {
  id: "q1",
  state: "BUZZ_OPEN",
  roundId: "2",
  buzzStatus: "WAITING_FOR_BUZZ"
};

test("applies winner and submission events to the current round", () => {
  const won = applyTeacherBuzzEvent([question], {
    type: "BUZZ_RESULT",
    payload: { questionId: "q1", roundId: "2", successful: true, displayName: "Amy" }
  });
  assert.equal(won[0].buzzStatus, "WINNER_ANSWERING");
  assert.equal(won[0].winnerDisplayName, "Amy");

  const submitted = applyTeacherBuzzEvent(won, {
    type: "SUBMISSION_CREATED",
    payload: { questionId: "q1", roundId: "2", status: "RECEIVED", reviewStatus: "PENDING_REVIEW" }
  });
  assert.equal(submitted[0].buzzStatus, "SUBMITTED_PENDING_REVIEW");
});

test("ignores old-round events and clears current reset state", () => {
  const ignored = applyTeacherBuzzEvent([question], {
    type: "BUZZ_RESULT",
    payload: { questionId: "q1", roundId: "1", successful: true }
  });
  assert.equal(ignored[0].buzzStatus, "WAITING_FOR_BUZZ");

  const reset = applyTeacherBuzzEvent([question], {
    type: "BUZZ_RESET",
    payload: { questionId: "q1", roundId: "2" }
  });
  assert.equal(reset[0].state, "READY");
  assert.equal(reset[0].buzzStatus, "NOT_OPEN");
});

test("accepts packageId as the package-scoped realtime identifier", () => {
  const won = applyTeacherBuzzEvent([question], {
    type: "BUZZ_RESULT",
    payload: { packageId: "q1", roundId: "2", successful: true, displayName: "Package winner" }
  });
  assert.equal(won[0].buzzStatus, "WINNER_ANSWERING");
  assert.equal(won[0].winnerDisplayName, "Package winner");
});
