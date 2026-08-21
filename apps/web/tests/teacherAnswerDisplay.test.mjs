import test from "node:test";
import assert from "node:assert/strict";
import { fastestDistinctGroupAnswers } from "../dist/teacher/teacherAnswerDisplay.js";

function answer(submissionId, groupNumber, userId = submissionId) {
  return {
    submissionId,
    questionId: "q1",
    userId,
    isOwnSubmission: false,
    displayName: `${userId} / 第${groupNumber}組`,
    answer: submissionId,
    reviewStatus: "PENDING_REVIEW",
    groupNumber,
    enteredByTeacher: false,
    groupChallenged: false
  };
}

test("shows only the first two distinct groups in receipt order", () => {
  const answers = [
    answer("first-g1", 1),
    answer("second-g1", 1),
    answer("first-g2", 2),
    answer("first-g3", 3)
  ];

  assert.deepEqual(
    fastestDistinctGroupAnswers(answers).map((item) => item.submissionId),
    ["first-g1", "first-g2"]
  );
});

test("keeps ungrouped legacy submissions distinct by user", () => {
  const answers = [
    answer("legacy-a", "", "student-a"),
    answer("legacy-b", "", "student-b"),
    answer("legacy-c", "", "student-c")
  ];

  assert.deepEqual(
    fastestDistinctGroupAnswers(answers).map((item) => item.submissionId),
    ["legacy-a", "legacy-b"]
  );
});
