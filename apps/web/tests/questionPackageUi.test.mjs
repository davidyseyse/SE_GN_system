import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const teacherSource = readFileSync(new URL("../src/teacher/TeacherDashboardPage.tsx", import.meta.url), "utf8");
const studentSource = readFileSync(new URL("../src/student/StudentPlayPage.tsx", import.meta.url), "utf8");

test("teacher authoring starts with one stable-keyed subquestion", () => {
  assert.match(
    teacherSource,
    /useState<Array<\{ clientId: string; prompt: string; targetCell: string \}>>\(\[\s*\{ clientId: crypto\.randomUUID\(\), prompt: "", targetCell: "" \}/
  );
  assert.match(teacherSource, /key=\{item\.clientId\}/);
});

test("only appended subquestions expose removal and add appends before creation", () => {
  assert.match(teacherSource, /\{index > 0 \? \(/);
  assert.match(teacherSource, />\s*移除子題\s*</);
  assert.match(teacherSource, />＋新增子題</);
  assert.match(teacherSource, /current\[0\]\?\.clientId === clientId \? current/);
});

test("package form validates duplicate cells and resets only after successful await", () => {
  assert.match(teacherSource, /hasDuplicateCells/);
  assert.match(teacherSource, /await createQuestionPackage/);
  assert.match(
    teacherSource,
    /setQuestionItems\(\[\{ clientId: crypto\.randomUUID\(\), prompt: "", targetCell: "" \}\]\)/
  );
});

test("student package uses one buzz action, item-keyed answers, and no event feed", () => {
  assert.match(studentSource, /items\.map\(\(item\) => \(\s*<label key=\{item\.id\}>/);
  assert.match(studentSource, /submitPackageAnswers/);
  assert.doesNotMatch(studentSource, /<h3>即時訊息<\/h3>/);
  assert.doesNotMatch(studentSource, /formatRealtimeEventType/);
  assert.match(studentSource, /formatRealtimeStatus\(realtime\.status\)/);
  assert.match(studentSource, /\{item\.prompt\}/);
  assert.match(studentSource, /currentAnswers\.some\(\(submission\) => submission\.userId === currentUserId\)/);
});
