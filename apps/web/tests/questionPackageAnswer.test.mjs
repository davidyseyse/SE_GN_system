import test from "node:test";
import assert from "node:assert/strict";
import {
  formatPackageAnswer,
  formatTeacherAnswerAttribution,
  parsePackageAnswers
} from "../dist/lib/questionPackageAnswer.js";

const items = [
  { id: "item-1", ordinal: 1, prompt: "第一題說明", targetCell: "A1" },
  { id: "item-2", ordinal: 2, prompt: "第二題說明", targetCell: "A2" }
];

test("formats item-id keyed answers using subquestion prompts", () => {
  const formatted = formatPackageAnswer(
    '{"item-1":"1233","item-2":"4455"}',
    items
  );
  assert.equal(formatted, "第一題說明:1233,第二題說明:4455");
});

test("keeps legacy plain answers readable", () => {
  assert.equal(formatPackageAnswer("legacy answer", items), "legacy answer");
  assert.equal(parsePackageAnswers("legacy answer"), null);
});

test("formats original identity and challenge group without ambiguous concatenation", () => {
  assert.equal(formatTeacherAnswerAttribution("1 / 第1組", 1, false, false), "1 / 第1組");
  assert.equal(formatTeacherAnswerAttribution("1 / 第1組", 1, false, true), "1 / 第1組　挑戰：第1組");
  assert.equal(formatTeacherAnswerAttribution("1 / 第1組", 6, false, true), "1 / 第1組　挑戰：第6組");
  assert.equal(formatTeacherAnswerAttribution("teacher", 6, true, true), "挑戰：第6組");
  assert.doesNotMatch(formatTeacherAnswerAttribution("1 / 第1組", 6, false, true), /第1組／第6組/);
});
