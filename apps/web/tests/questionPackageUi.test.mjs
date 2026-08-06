import test from "node:test";
import assert from "node:assert/strict";
import { readFileSync } from "node:fs";

const teacherSource = readFileSync(new URL("../src/teacher/TeacherDashboardPage.tsx", import.meta.url), "utf8");
const studentSource = readFileSync(new URL("../src/student/StudentPlayPage.tsx", import.meta.url), "utf8");
const stylesSource = readFileSync(new URL("../src/styles.css", import.meta.url), "utf8");

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

test("teacher configures a positive whole-number answer limit instead of a hard-coded value", () => {
  assert.match(teacherSource, /setAnswerTimeLimitSeconds\] = useState\("30"\)/);
  assert.match(teacherSource, /type="number"/);
  assert.match(teacherSource, /min="1"/);
  assert.match(teacherSource, /step="1"/);
  assert.match(teacherSource, /Number\.isInteger\(configuredAnswerTimeLimitSeconds\)/);
  assert.match(teacherSource, /answerTimeLimitSeconds: configuredAnswerTimeLimitSeconds/);
  assert.doesNotMatch(teacherSource, /answerTimeLimitSeconds:\s*30/);
});

test("student package uses one buzz action, item-keyed answers, and no event feed", () => {
  assert.match(studentSource, /items\.map\(\(item\) => \(\s*<label key=\{item\.id\}>/);
  assert.match(studentSource, /submitPackageAnswers/);
  assert.doesNotMatch(studentSource, /<h3>即時訊息<\/h3>/);
  assert.doesNotMatch(studentSource, /formatRealtimeEventType/);
  assert.match(studentSource, /formatRealtimeStatus\(realtime\.status\)/);
  assert.match(studentSource, /\{item\.prompt\}/);
  assert.match(studentSource, /currentAnswers\.some\(\(submission\) => submission\.isOwnSubmission\)/);
  assert.doesNotMatch(studentSource, /currentAnswers\.map\(\(submission\) => \(/);
  assert.doesNotMatch(studentSource, /currentAnswers\.filter/);
  assert.doesNotMatch(studentSource, /className="submitted-answer"/);
  assert.doesNotMatch(studentSource, /提交答案：/);
  assert.doesNotMatch(studentSource, /<span className="pill pill-won">✓ 已作答<\/span>/);
});

test("student answer refreshes ignore stale responses and reconcile after realtime resync", () => {
  assert.match(studentSource, /createLatestRequestGate/);
  assert.match(studentSource, /answerRefreshGateRef\.current\.isCurrent\(requestId\)/);
  assert.match(studentSource, /Promise\.allSettled/);
  assert.match(studentSource, /\[refreshStudentView, realtime\.events\]/);
  assert.match(studentSource, /await refreshStudentView\(\)/);
});

test("student timeout cancellation is round-scoped and displays the expiration notice", () => {
  assert.match(studentSource, /reason\?: string/);
  assert.match(studentSource, /timeoutCancellationNotice\(/);
  assert.match(studentSource, /realtimeEventApplies\(latestEvent\.type, eventRoundId, currentRoundId\)/);
  assert.match(studentSource, /\[questionId\]: expirationNotice/);
  assert.match(studentSource, /const canBuzz = state === "BUZZ_OPEN" && winnerUserId === ""/);
});

test("student winner countdown uses the server deadline and waits for authoritative release", () => {
  assert.match(studentSource, /answerDeadlineAt\?: string/);
  assert.match(studentSource, /remainingAnswerSeconds\(/);
  assert.match(studentSource, /countdownSeconds !== null/);
  assert.match(studentSource, /剩餘 \$\{countdownSeconds\} 秒/);
  assert.match(studentSource, /: "時間到"/);
  assert.match(studentSource, /isWinner && !hasSubmitted/);
});

test("student winner countdown is pinned to the question card top-right corner", () => {
  assert.match(stylesSource, /\.question-list \.question-stage \{[\s\S]*?position: relative;/);
  assert.match(stylesSource, /\.answer-countdown \{[\s\S]*?position: absolute;[\s\S]*?top: 1rem;[\s\S]*?right: 1rem;/);
  assert.match(stylesSource, /\.question-list \.question-stage > strong:first-child \{[\s\S]*?padding-right: 7\.5rem;/);
});
