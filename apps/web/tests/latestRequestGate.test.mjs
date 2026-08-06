import test from "node:test";
import assert from "node:assert/strict";
import { createLatestRequestGate } from "../dist/lib/latestRequestGate.js";

function deferred() {
  let resolve;
  const promise = new Promise((complete) => {
    resolve = complete;
  });
  return { promise, resolve };
}

test("an older answer refresh cannot replace the latest classroom snapshot", async () => {
  const gate = createLatestRequestGate();
  const olderResponse = deferred();
  const latestResponse = deferred();
  let visibleAnswers = [];

  async function refresh(response) {
    const requestId = gate.begin();
    const answers = await response;
    if (gate.isCurrent(requestId)) {
      visibleAnswers = answers;
    }
  }

  const olderRefresh = refresh(olderResponse.promise);
  const latestRefresh = refresh(latestResponse.promise);

  latestResponse.resolve(["student-a", "student-b"]);
  await latestRefresh;
  olderResponse.resolve(["student-a"]);
  await olderRefresh;

  assert.deepEqual(visibleAnswers, ["student-a", "student-b"]);
});

test("invalidating the gate prevents an unmounted view from accepting a response", () => {
  const gate = createLatestRequestGate();
  const requestId = gate.begin();
  gate.invalidate();
  assert.equal(gate.isCurrent(requestId), false);
});
