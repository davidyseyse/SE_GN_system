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

test("teacher does not configure an individual answer deadline", () => {
  assert.doesNotMatch(teacherSource, /setAnswerTimeLimitSeconds/);
  assert.doesNotMatch(teacherSource, /作答時限/);
  assert.doesNotMatch(teacherSource, /answerTimeLimitSeconds:/);
});

test("teacher hides the fixed global group limit and displays usage", () => {
  assert.doesNotMatch(teacherSource, /setSuccessfulBuzzLimit/);
  assert.doesNotMatch(teacherSource, /successfulBuzzLimit: parsedSuccessfulBuzzLimit/);
  assert.doesNotMatch(teacherSource, /每組搶答成功上限必須是正整數/);
  assert.doesNotMatch(teacherSource, /value=\{successfulBuzzLimit\}/);
  assert.match(teacherSource, /member\.groupSuccessfulBuzzCount/);
  assert.match(teacherSource, /今日成功搶答/);
  assert.match(teacherSource, /className="classroom-create-form"/);
  assert.match(teacherSource, /className="classroom-action-row"/);
  assert.doesNotMatch(teacherSource, /hero-action-button-primary/);
  assert.equal((teacherSource.match(/hero-action-button hero-action-button-secondary/g) ?? []).length, 2);
});

test("teacher can reset all current-day group answer limits independently", () => {
  assert.match(teacherSource, /resetAnswerLimits/);
  assert.match(teacherSource, /確定要重置所有組別的答題限制嗎？/);
  assert.match(teacherSource, />\s*重置全部題目\s*<\/button>[\s\S]*?>\s*重置答題限制\s*<\/button>/);
  assert.match(teacherSource, /await resetAnswerLimits\(teacherToken, classroomCode\)/);
  assert.match(teacherSource, /await getMembers\(teacherToken, classroomCode\)/);
  assert.match(teacherSource, /已重置所有組別今天的答題限制。/);
  assert.match(teacherSource, /目前沒有需要重置的答題限制。/);
});

test("teacher selects owned classrooms and confirms deletion", () => {
  assert.match(teacherSource, /listClassrooms/);
  assert.match(teacherSource, /const \[resumeClassroomCode, setResumeClassroomCode\] = useState\(""\)/);
  assert.match(teacherSource, /aria-label="選擇現有課堂"/);
  assert.match(teacherSource, /<option value="">請選擇現有課堂<\/option>/);
  assert.doesNotMatch(teacherSource, /placeholder="輸入課堂代碼以重返既有課堂"/);
  assert.match(teacherSource, /setResumeClassroomCode\(result\.classroom\.code\)/);
  assert.match(teacherSource, /window\.confirm/);
  assert.match(teacherSource, /deleteClassroom\(teacherToken, deletedCode\)/);
  assert.match(teacherSource, />刪除課堂<\/button>/);
  assert.match(stylesSource, /\.classroom-resume-form > select/);
});

test("teacher can calculate linked-sheet group scores on demand", () => {
  assert.match(teacherSource, /getSheetGroupStatistics/);
  assert.match(teacherSource, /aria-label="統計用表單配置"/);
  assert.match(teacherSource, /"統計分數"/);
  assert.match(teacherSource, /sheetStatistics\.successfulGroupCount/);
  assert.match(teacherSource, /sheetStatistics\.totalSuccessfulAnswers/);
  assert.match(teacherSource, /group\.successfulAnswerCount/);
  assert.match(teacherSource, /總得分/);
  assert.match(teacherSource, /successfulAnswerCount} 分/);
  assert.match(stylesSource, /\.group-score-list/);
});

test("teacher confirms sheet configuration cascade deletion and reports affected packages", () => {
  assert.match(teacherSource, /確定要刪除/);
  assert.match(teacherSource, /作答紀錄與搶答紀錄也會一併刪除/);
  assert.match(teacherSource, /const result = await deleteSheetConfig/);
  assert.match(teacherSource, /result\.deletedPackageCount/);
  assert.match(teacherSource, /setSourceSheetConfigId\(""\)/);
  assert.match(teacherSource, /setAnswerSheetConfigId\(""\)/);
  assert.match(teacherSource, /setGeneratedScan\(null\)/);
  assert.match(teacherSource, /刪除失敗/);
});

test("teacher edits current answers inside the question card and removes the legacy review panel", () => {
  assert.doesNotMatch(teacherSource, /\/api\/reports\/submissions/);
  assert.doesNotMatch(teacherSource, /selectedQuestionId/);
  assert.match(teacherSource, /editingGroupNumber/);
  assert.match(teacherSource, /updateEditingPackageAnswer/);
  assert.match(teacherSource, /handleCreateManualAnswer/);
  assert.match(teacherSource, /questionState === "LOCKED" && currentAnswers\.length === 0/);
  assert.match(teacherSource, />修改答案</);
  assert.match(teacherSource, /parsePackageAnswers\(editingAnswer\)\?\.\[item\.id\]/);
  assert.match(teacherSource, /formatTeacherAnswerAttribution/);
  assert.match(teacherSource, /\/api\/submissions\/answer\/retry/);
  assert.match(teacherSource, /答案已儲存，但 Google Sheets 同步失敗/);
  assert.match(teacherSource, />重試 Google Sheets 同步</);
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

test("student explicit cancellation remains round-scoped", () => {
  assert.doesNotMatch(studentSource, /timeoutCancellationNotice\(/);
  assert.match(studentSource, /realtimeEventApplies\(latestEvent\.type, eventRoundId, currentRoundId\)/);
  assert.match(studentSource, /eventTargetsCurrentStudent/);
  assert.match(studentSource, /const canBuzz = state === "BUZZ_OPEN" && answersComplete/);
});

test("student attempt has no countdown or deadline", () => {
  assert.doesNotMatch(studentSource, /answerDeadlineAt/);
  assert.doesNotMatch(studentSource, /remainingAnswerSeconds/);
  assert.doesNotMatch(studentSource, /countdownSeconds/);
  assert.doesNotMatch(studentSource, /role="timer"/);
  assert.match(studentSource, /ownBuzzAttemptRecorded/);
});

test("student prepares complete answers before buzzing and immediately submits", () => {
  assert.match(studentSource, /setLocallyBuzzedQuestionIds/);
  assert.match(studentSource, /arePackageAnswersComplete/);
  assert.match(studentSource, /完成所有答案後即可搶答/);
  assert.match(studentSource, /搶答並送出答案/);
  assert.match(studentSource, /const buzzResult = await submitBuzz/);
  assert.match(studentSource, /const submissionResult = await submitPackageAnswers/);
  assert.match(studentSource, /await cancelBuzz[\s\S]*\.catch/);
  assert.match(studentSource, /SUCCESS_LIMIT_REACHED/);
  assert.match(studentSource, /NOT_FASTEST/);
  assert.match(studentSource, /本次未搶答成功，答案未寫入系統。/);
  assert.doesNotMatch(studentSource, /你的組別已達全系統搶答成功上限/);
  assert.doesNotMatch(studentSource, /組已達搶答成功上限/);
  assert.doesNotMatch(studentSource, /groupSuccessfulBuzzCount\}\/\$\{buzzResult\.successfulBuzzLimit/);
  assert.doesNotMatch(studentSource, /已被搶答/);
  assert.doesNotMatch(studentSource, /取消鎖定/);
});

test("student styles do not reserve space for a countdown", () => {
  assert.doesNotMatch(stylesSource, /\.answer-countdown/);
});

test("teacher scans independent source and answer worksheets with no mapping mode", () => {
  assert.match(teacherSource, /sourceSheetConfigId/);
  assert.match(teacherSource, /answerSheetConfigId/);
  assert.match(teacherSource, /sourceWorksheet/);
  assert.match(teacherSource, /answerWorksheet/);
  assert.match(teacherSource, /自動偵測生成題目/);
  assert.doesNotMatch(teacherSource, /mappingMode|對應方式/);
});

test("teacher is warned that accepted answers overwrite occupied destination cells", () => {
  assert.match(teacherSource, /直接覆蓋該格原有的值或公式/);
});

test("generated preview supports selection, text edits, confirmation, and cancellation", () => {
  assert.match(teacherSource, /updateGeneratedPackage/);
  assert.match(teacherSource, /updateGeneratedItem/);
  assert.match(teacherSource, /confirmGeneratedQuestions/);
  assert.match(teacherSource, /setGeneratedScan\(null\)/);
  assert.match(stylesSource, /\.generated-sheet-selectors/);
});

test("READY package card provides text-only editing", () => {
  assert.match(teacherSource, /questionState === "READY"/);
  assert.match(teacherSource, />編輯<\/button>/);
  assert.match(teacherSource, /updateQuestionPackageDescriptions/);
  assert.doesNotMatch(teacherSource, /editingPackageTargetCell/);
});
