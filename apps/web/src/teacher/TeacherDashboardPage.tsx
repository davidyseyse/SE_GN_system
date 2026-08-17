import { useEffect, useMemo, useState } from "react";
import {
  type QuestionView,
  type SubmittedAnswerView,
  type ClassroomView,
  type SheetGroupStatisticsView,
  type GeneratedQuestionScan,
  batchQuestionAction,
  createClassroom,
  createQuestionPackage,
  createSheetConfig,
  deleteClassroom,
  deleteSheetConfig,
  getCurrentAnswers,
  getMembers,
  getSummary,
  getSheetGroupStatistics,
  listSheetWorksheets,
  scanGeneratedQuestions,
  confirmGeneratedQuestions,
  updateQuestionPackageDescriptions,
  joinClassroom,
  listClassrooms,
  listQuestions,
  listSheetConfigs,
  resetAnswerLimits,
  resetBuzz,
  updateQuestionState
} from "../lib/api";
import { applyTeacherBuzzEvent } from "./teacherBuzzState";
import { readSession, writeSession } from "../lib/session";
import {
  formatPresenceStatus,
  formatQuestionState,
  formatQuestionType,
  formatRealtimeEventType,
  formatRealtimeStatus
} from "../lib/text";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";
import { formatPackageAnswer, formatTeacherAnswerAttribution, parsePackageAnswers } from "../lib/questionPackageAnswer";

type RealtimeEventView = {
  type: string;
  occurredAt: string;
};

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const allowedQuestionTransitions: Record<string, string[]> = {
  DRAFT: ["READY", "CLOSED"],
  READY: ["BUZZ_OPEN", "ANSWER_OPEN", "CLOSED"],
  BUZZ_OPEN: ["ANSWER_OPEN", "LOCKED", "CLOSED"],
  ANSWER_OPEN: ["LOCKED", "REVIEW", "PUBLISHED", "CLOSED"],
  LOCKED: ["REVIEW", "PUBLISHED", "CLOSED"],
  REVIEW: ["PUBLISHED", "CLOSED"],
  PUBLISHED: ["CLOSED"],
  CLOSED: []
};

const buzzStatusLabels: Record<string, string> = {
  NOT_OPEN: "○ 未開放",
  WAITING_FOR_BUZZ: "● 開放搶答，等待學生",
  WINNER_ANSWERING: "✎ 已搶答，作答中",
  SUBMITTED_PENDING_REVIEW: "✓ 已作答",
  STOPPED: "■ 已停止，待檢討"
};

async function authorizedGet<T>(path: string, token: string): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    headers: {
      Authorization: `Bearer ${token}`
    }
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error ?? "請求失敗");
  }
  return data as T;
}

async function authorizedPost<T>(path: string, token: string, body: unknown): Promise<T> {
  const response = await fetch(`${API_BASE_URL}${path}`, {
    method: "POST",
    headers: {
      "Content-Type": "application/json",
      Authorization: `Bearer ${token}`
    },
    body: JSON.stringify(body)
  });
  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error ?? "請求失敗");
  }
  return data as T;
}

export function TeacherDashboardPage() {
  const stored = readSession();
  const teacherToken = stored?.token ?? null;
  const [classroomCode, setClassroomCode] = useState(stored?.classroomCode ?? "");
  const [resumeClassroomCode, setResumeClassroomCode] = useState("");
  const [teacherClassrooms, setTeacherClassrooms] = useState<ClassroomView[]>([]);
  const [classroomName, setClassroomName] = useState("課堂 A");
  const [classroomSuccessfulBuzzLimit, setClassroomSuccessfulBuzzLimit] = useState(1);
  const [questionPackageTitle, setQuestionPackageTitle] = useState("");
  const [questionItems, setQuestionItems] = useState<Array<{ clientId: string; prompt: string; targetCell: string }>>([
    { clientId: crypto.randomUUID(), prompt: "", targetCell: "" }
  ]);
  const [sheetConfigs, setSheetConfigs] = useState<Array<{ id: string; name: string; sheetId: string }>>([]);
  const [sheetConfigName, setSheetConfigName] = useState("");
  const [sheetIdInput, setSheetIdInput] = useState("");
  const [selectedSheetConfigId, setSelectedSheetConfigId] = useState("");
  const [statisticsSheetConfigId, setStatisticsSheetConfigId] = useState("");
  const [sheetStatistics, setSheetStatistics] = useState<SheetGroupStatisticsView | null>(null);
  const [statisticsLoading, setStatisticsLoading] = useState(false);
  const [sourceSheetConfigId, setSourceSheetConfigId] = useState("");
  const [sourceWorksheets, setSourceWorksheets] = useState<string[]>([]);
  const [sourceWorksheet, setSourceWorksheet] = useState("");
  const [answerSheetConfigId, setAnswerSheetConfigId] = useState("");
  const [answerWorksheets, setAnswerWorksheets] = useState<string[]>([]);
  const [answerWorksheet, setAnswerWorksheet] = useState("");
  const [generatedScan, setGeneratedScan] = useState<GeneratedQuestionScan | null>(null);
  const [scanLoading, setScanLoading] = useState(false);
  const [sheetAccessError, setSheetAccessError] = useState("");
  const [editingPackageId, setEditingPackageId] = useState("");
  const [editingPackageTitle, setEditingPackageTitle] = useState("");
  const [editingPackageItems, setEditingPackageItems] = useState<Record<string, string>>({});
  const [questions, setQuestions] = useState<QuestionView[]>([]);
  const [answersByQuestionId, setAnswersByQuestionId] = useState<Record<string, SubmittedAnswerView[]>>({});
  const [editingSubmissionId, setEditingSubmissionId] = useState("");
  const [editingAnswer, setEditingAnswer] = useState("");
  const [editingGroupNumber, setEditingGroupNumber] = useState("");
  const [manualQuestionId, setManualQuestionId] = useState("");
  const [retrySheetSubmissionId, setRetrySheetSubmissionId] = useState("");
  const [members, setMembers] = useState<Array<Record<string, unknown>>>([]);
  const [summary, setSummary] = useState<Record<string, unknown> | null>(null);
  const [notice, setNotice] = useState("請先建立課堂，或輸入課堂代碼重返既有課堂。");
  const realtime = useRealtimeFeed(teacherToken, classroomCode || null);

  const lastRealtimeEvent = useMemo(
    () => realtime.events.length > 0 ? realtime.events[realtime.events.length - 1] : null,
    [realtime.events]
  );

  useEffect(() => {
    if (!teacherToken) {
      return;
    }
    void listSheetConfigs(teacherToken).then((result) => setSheetConfigs(result.configs));
    void listClassrooms(teacherToken).then((result) => setTeacherClassrooms(result.classrooms));
  }, [teacherToken]);

  useEffect(() => {
    setGeneratedScan(null);
    setSheetAccessError("");
    setSourceWorksheet("");
    setSourceWorksheets([]);
    if (!teacherToken || !sourceSheetConfigId) return;
    void listSheetWorksheets(teacherToken, sourceSheetConfigId).then((result) => {
      setSourceWorksheets(result.worksheets);
      setSourceWorksheet(result.worksheets[0] ?? "");
    }).catch((error) => {
      const message = error instanceof Error ? error.message : "無法讀取來源工作表";
      setSheetAccessError(message); setNotice(message);
    });
  }, [teacherToken, sourceSheetConfigId]);

  useEffect(() => {
    setGeneratedScan(null);
    setSheetAccessError("");
    setAnswerWorksheet("");
    setAnswerWorksheets([]);
    if (!teacherToken || !answerSheetConfigId) return;
    void listSheetWorksheets(teacherToken, answerSheetConfigId).then((result) => {
      setAnswerWorksheets(result.worksheets);
      setAnswerWorksheet(result.worksheets[0] ?? "");
    }).catch((error) => {
      const message = error instanceof Error ? error.message : "無法讀取答案工作表";
      setSheetAccessError(message); setNotice(message);
    });
  }, [teacherToken, answerSheetConfigId]);

  useEffect(() => {
    if (!teacherToken || !classroomCode) {
      return;
    }
    void Promise.all([
      listQuestions(teacherToken, classroomCode).then((result) => setQuestions(result.questions)),
      getMembers(teacherToken, classroomCode).then((result) => {
        setMembers(result.members as unknown as Array<Record<string, unknown>>);
        setClassroomSuccessfulBuzzLimit(result.successfulBuzzLimit);
      }),
      getSummary(teacherToken, classroomCode).then((result) => setSummary(result as unknown as Record<string, unknown>))
    ]);
  }, [teacherToken, classroomCode, lastRealtimeEvent]);

  const questionIdsKey = useMemo(
    () => questions.map((question) => String(question.id)).join(","),
    [questions]
  );

  useEffect(() => {
    if (!teacherToken || !classroomCode || !questionIdsKey) {
      setAnswersByQuestionId({});
      return;
    }
    const questionIds = questionIdsKey.split(",");
    void Promise.all(questionIds.map((questionId) =>
      getCurrentAnswers(teacherToken, classroomCode, questionId)
    )).then((results) => {
      setAnswersByQuestionId(Object.fromEntries(
        results.map((result) => [result.questionId, result.answers])
      ));
    });
  }, [teacherToken, classroomCode, questionIdsKey, lastRealtimeEvent]);

  useEffect(() => {
    setQuestions((current) => applyTeacherBuzzEvent(current, lastRealtimeEvent));
  }, [lastRealtimeEvent]);

  async function handleCreateClassroom() {
    if (!teacherToken || !stored) {
      return;
    }
    const settings = JSON.stringify({
      buzzBonus: 5,
      wrongAnswerPenalty: 2,
      showAnswer: false
    });
    const result = await createClassroom(teacherToken, classroomName, settings);
    setClassroomSuccessfulBuzzLimit(1);
    setClassroomCode(result.classroom.code);
    setResumeClassroomCode(result.classroom.code);
    setTeacherClassrooms((current) => [
      result.classroom,
      ...current.filter((classroom) => classroom.id !== result.classroom.id)
    ]);
    writeSession({
      ...stored,
      classroomCode: result.classroom.code,
      classroomId: result.classroom.id
    });
    setNotice(`課堂 ${result.classroom.code} 已建立。`);
  }

  async function handleResumeClassroom() {
    if (!teacherToken || !stored || !resumeClassroomCode.trim()) {
      return;
    }
    const joined = await joinClassroom(teacherToken, resumeClassroomCode.trim().toUpperCase());
    setClassroomCode(joined.classroom.code);
    setResumeClassroomCode(joined.classroom.code);
    writeSession({
      ...stored,
      classroomCode: joined.classroom.code,
      classroomId: joined.classroom.id
    });
    setNotice(`已重返課堂 ${joined.classroom.code}。`);
  }

  async function handleDeleteClassroom() {
    if (!teacherToken || !stored || !resumeClassroomCode) {
      return;
    }
    const selected = teacherClassrooms.find((classroom) => classroom.code === resumeClassroomCode);
    const description = selected ? `${selected.name}（${selected.code}）` : resumeClassroomCode;
    if (!window.confirm(`確定要刪除課堂 ${description}？課堂內的題目與作答紀錄也會刪除。`)) {
      return;
    }

    const deletedCode = resumeClassroomCode;
    await deleteClassroom(teacherToken, deletedCode);
    setTeacherClassrooms((current) => current.filter((classroom) => classroom.code !== deletedCode));
    setResumeClassroomCode("");
    if (classroomCode === deletedCode) {
      setClassroomCode("");
      setQuestions([]);
      setAnswersByQuestionId({});
      setMembers([]);
      setSummary(null);
      writeSession({
        token: stored.token,
        userId: stored.userId,
        displayName: stored.displayName,
        role: stored.role
      });
    }
    setNotice(`課堂 ${deletedCode} 已刪除。`);
  }

  async function handleCreateSheetConfig() {
    if (!teacherToken) {
      return;
    }
    const result = await createSheetConfig(teacherToken, sheetConfigName, sheetIdInput);
    setSheetConfigs((current) => [...current, result.config]);
    setSheetConfigName("");
    setSheetIdInput("");
    setNotice("Google Sheet 配置已新增。");
  }

  async function handleDeleteSheetConfig(configId: string) {
    if (!teacherToken) {
      return;
    }
    await deleteSheetConfig(teacherToken, configId);
    setSheetConfigs((current) => current.filter((config) => config.id !== configId));
    if (selectedSheetConfigId === configId) {
      setSelectedSheetConfigId("");
    }
    if (statisticsSheetConfigId === configId) {
      setStatisticsSheetConfigId("");
      setSheetStatistics(null);
    }
    setNotice("Google Sheet 配置已刪除。");
  }

  async function handleCalculateSheetStatistics() {
    if (!teacherToken || !statisticsSheetConfigId) {
      return;
    }
    setStatisticsLoading(true);
    setSheetStatistics(null);
    try {
      const result = await getSheetGroupStatistics(teacherToken, statisticsSheetConfigId);
      setSheetStatistics(result);
      setNotice(`已完成 ${result.config.name} 的搶答成功組別統計。`);
    } catch (error) {
      setNotice(error instanceof Error ? `統計失敗：${error.message}` : "統計失敗，請稍後再試。");
    } finally {
      setStatisticsLoading(false);
    }
  }

  async function handleCreateQuestion() {
    if (!teacherToken || !classroomCode) {
      return;
    }
    const result = await createQuestionPackage(teacherToken, classroomCode, {
      title: questionPackageTitle,
      sheetConfigId: selectedSheetConfigId,
      items: questionItems.map(({ prompt, targetCell }) => ({ prompt, targetCell })),
      score: 10
    });
    setQuestions((current) => [...current, result.package]);
    setQuestionPackageTitle("");
    setQuestionItems([{ clientId: crypto.randomUUID(), prompt: "", targetCell: "" }]);
    setNotice("題目已建立。");
  }

  async function handleScanGeneratedQuestions() {
    if (!teacherToken || !classroomCode || !sourceSheetConfigId || !sourceWorksheet
        || !answerSheetConfigId || !answerWorksheet) return;
    setScanLoading(true);
    setSheetAccessError("");
    setGeneratedScan(null);
    try {
      const result = await scanGeneratedQuestions(teacherToken, {
        classroomCode, sourceSheetConfigId, sourceWorksheet, answerSheetConfigId, answerWorksheet
      });
      setGeneratedScan(result);
      setNotice(result.packages.length ? `找到 ${result.packages.length} 個題組。` : "沒有偵測到題目標記。");
    } catch (error) {
      const message = error instanceof Error ? error.message : "自動偵測失敗";
      setSheetAccessError(message); setNotice(message);
    } finally {
      setScanLoading(false);
    }
  }

  function updateGeneratedPackage(packageCode: string, field: "title" | "selected", value: string | boolean) {
    setGeneratedScan((current) => current ? { ...current, packages: current.packages.map((item) =>
      item.code === packageCode ? { ...item, [field]: value } : item) } : current);
  }

  function updateGeneratedItem(packageCode: string, sourceCell: string, prompt: string) {
    setGeneratedScan((current) => current ? { ...current, packages: current.packages.map((item) =>
      item.code === packageCode ? { ...item, items: item.items.map((child) =>
        child.sourceCell === sourceCell ? { ...child, prompt } : child) } : item) } : current);
  }

  async function handleConfirmGeneratedQuestions() {
    if (!teacherToken || !generatedScan) return;
    try {
      const result = await confirmGeneratedQuestions(teacherToken, generatedScan);
      const refreshed = await listQuestions(teacherToken, classroomCode);
      setQuestions(refreshed.questions);
      setGeneratedScan(null);
      setNotice(`已建立 ${result.createdCount} 個題組。`);
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "建立自動偵測題目失敗");
    }
  }

  function beginPackageEdit(question: QuestionView) {
    setEditingPackageId(String(question.id));
    setEditingPackageTitle(String(question.title ?? question.prompt));
    setEditingPackageItems(Object.fromEntries((question.items ?? []).map((item) => [item.id, item.prompt])));
  }

  async function handleSavePackageEdit(question: QuestionView) {
    if (!teacherToken || !classroomCode) return;
    try {
      const result = await updateQuestionPackageDescriptions(teacherToken, classroomCode,
        String(question.id), editingPackageTitle, (question.items ?? []).map((item) => ({
          itemId: item.id, prompt: editingPackageItems[item.id] ?? ""
        })));
      setQuestions((current) => current.map((item) => String(item.id) === String(question.id) ? result.package : item));
      setEditingPackageId("");
      setNotice("題目敘述已更新。");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "更新題目敘述失敗");
    }
  }

  async function handleStateChange(questionId: string, targetState: string) {
    if (!teacherToken || !classroomCode) {
      return;
    }
    const question = questions.find((item) => String(item.id) === questionId);
    const currentState = String(question?.state ?? "");
    if (!allowedQuestionTransitions[currentState]?.includes(targetState)) {
      setNotice(`題目無法從「${formatQuestionState(currentState)}」直接切換到「${formatQuestionState(targetState)}」。`);
      return;
    }
    await updateQuestionState(teacherToken, classroomCode, questionId, targetState);
    const result = await listQuestions(teacherToken, classroomCode);
    setQuestions(result.questions);
    setNotice(`題目已切換為「${formatQuestionState(targetState)}」。`);
  }

  async function handleReset(questionId?: string) {
    if (!teacherToken || !classroomCode) {
      return;
    }
    if (!questionId) {
      if (!window.confirm("確定要重置目前課堂所有進行中或已停止的搶答題目嗎？")) {
        return;
      }
      await handleBatch("RESET_ALL");
      return;
    }
    await resetBuzz(teacherToken, classroomCode, questionId);
    const result = await listQuestions(teacherToken, classroomCode);
    setQuestions(result.questions);
    setNotice(questionId ? "該題已重置為未開放狀態。" : "所有題目已重置為未開放狀態。");
  }

  async function handleResetAnswerLimits() {
    if (!teacherToken || !classroomCode) {
      return;
    }
    if (!window.confirm("確定要重置所有組別的答題限制嗎？")) {
      return;
    }

    const result = await resetAnswerLimits(teacherToken, classroomCode);
    const refreshed = await getMembers(teacherToken, classroomCode);
    setMembers(refreshed.members as unknown as Array<Record<string, unknown>>);
    setClassroomSuccessfulBuzzLimit(refreshed.successfulBuzzLimit);
    setNotice(result.resetGroupCount > 0
      ? "已重置所有組別今天的答題限制。"
      : "目前沒有需要重置的答題限制。");
  }

  async function handleBatch(action: "OPEN_ALL" | "STOP_ALL" | "RESET_ALL") {
    if (!teacherToken || !classroomCode) return;
    const result = await batchQuestionAction(teacherToken, classroomCode, action);
    const counts = result.results.reduce<Record<string, number>>((current, item) => {
      current[item.result] = (current[item.result] ?? 0) + 1;
      return current;
    }, {});
    const refreshed = await listQuestions(teacherToken, classroomCode);
    setQuestions(refreshed.questions);
    const actionLabel = action === "OPEN_ALL"
      ? "一鍵開放"
      : action === "STOP_ALL"
        ? "一鍵停止"
        : "一鍵重置";
    setNotice(
      `${actionLabel}完成：已變更 ${counts.CHANGED ?? 0}、未變更 ${counts.UNCHANGED ?? 0}、略過 ${counts.SKIPPED ?? 0}、失敗 ${counts.FAILED ?? 0}。`
    );
  }

  async function handleCompleteAndReview(questionId: string) {
    if (!teacherToken || !classroomCode) return;
    const question = questions.find((item) => String(item.id) === questionId);
    const currentState = String(question?.state ?? "");
    if (currentState === "BUZZ_OPEN" || currentState === "ANSWER_OPEN") {
      await updateQuestionState(teacherToken, classroomCode, questionId, "LOCKED");
      const result = await listQuestions(teacherToken, classroomCode);
      setQuestions(result.questions);
    } else if (currentState !== "LOCKED") {
      setNotice("請先開放搶答，待學生提交答案後再完成搶答。");
      return;
    }
    setNotice("搶答已完成，可直接在題目卡中修改學生答案。");
  }

  async function handleUpdateAnswer() {
    if (!teacherToken || !editingSubmissionId || Number(editingGroupNumber) <= 0) {
      setNotice("請輸入有效的組別。");
      return;
    }
    const syncResult = await authorizedPost<{ syncStatus: string; syncError: string }>("/api/submissions/answer", teacherToken, {
      submissionId: editingSubmissionId,
      answer: editingAnswer,
      groupNumber: Number(editingGroupNumber)
    });
    setAnswersByQuestionId((current) => Object.fromEntries(Object.entries(current).map(([id, answers]) => [
      id,
      answers.map((submission) => submission.submissionId === editingSubmissionId
        ? { ...submission, answer: editingAnswer, groupNumber: Number(editingGroupNumber), groupChallenged: true }
        : submission)
    ])));
    setEditingSubmissionId("");
    setEditingAnswer("");
    setEditingGroupNumber("");
    if (syncResult.syncStatus === "RETRYABLE") {
      setRetrySheetSubmissionId(editingSubmissionId);
      setNotice(`答案已儲存，但 Google Sheets 同步失敗：${syncResult.syncError || "請稍後重試"}`);
    } else {
      setRetrySheetSubmissionId("");
      setNotice("學生答案已更新，Google Sheets 同步完成。");
    }
  }

  async function handleDeleteAnswer(submissionId: string) {
    if (!teacherToken) return;
    await authorizedPost("/api/submissions/delete", teacherToken, { submissionId });
    setAnswersByQuestionId((current) => Object.fromEntries(Object.entries(current).map(([id, answers]) => [
      id, answers.filter((submission) => submission.submissionId !== submissionId)
    ])));
    setNotice("學生答案已刪除。");
  }

  function updateEditingPackageAnswer(itemId: string, value: string) {
    const parsed = parsePackageAnswers(editingAnswer) ?? {};
    setEditingAnswer(JSON.stringify({ ...parsed, [itemId]: value }));
  }

  async function handleCreateManualAnswer(questionId: string) {
    if (!teacherToken || !classroomCode || Number(editingGroupNumber) <= 0) {
      setNotice("請輸入有效的組別。");
      return;
    }
    await authorizedPost("/api/submissions/manual", teacherToken, {
      classroomCode,
      questionId,
      answer: editingAnswer,
      groupNumber: Number(editingGroupNumber)
    });
    const result = await getCurrentAnswers(teacherToken, classroomCode, questionId);
    setAnswersByQuestionId((current) => ({ ...current, [questionId]: result.answers }));
    setManualQuestionId("");
    setEditingAnswer("");
    setEditingGroupNumber("");
    setNotice("答案已補登。");
  }

  async function handleRetrySheetSync(submissionId: string) {
    if (!teacherToken) return;
    const result = await authorizedPost<{ syncStatus: string; syncError: string }>(
      "/api/submissions/answer/retry", teacherToken, { submissionId }
    );
    if (result.syncStatus === "SYNCED" || result.syncStatus === "NOT_REQUIRED") {
      setRetrySheetSubmissionId("");
      setNotice("Google Sheets 重新同步完成。");
    } else {
      setNotice(`Google Sheets 同步仍失敗：${result.syncError || "請稍後重試"}`);
    }
  }

  async function handleDeleteQuestion(questionId: string) {
    if (!teacherToken || !classroomCode) return;
    await authorizedPost("/api/questions/delete", teacherToken, { classroomCode, questionId });
    setQuestions((current) => current.filter((question) => String(question.id) !== questionId));
    setAnswersByQuestionId((current) => {
      const next = { ...current };
      delete next[questionId];
      return next;
    });
    setNotice("題目已刪除。");
  }

  function updateQuestionItem(
    clientId: string,
    field: "prompt" | "targetCell",
    value: string
  ) {
    setQuestionItems((current) => current.map((item) =>
      item.clientId === clientId
        ? { ...item, [field]: field === "targetCell" ? value.toUpperCase() : value }
        : item
    ));
  }

  function addQuestionItem() {
    setQuestionItems((current) => [
      ...current,
      { clientId: crypto.randomUUID(), prompt: "", targetCell: "" }
    ]);
  }

  function removeQuestionItem(clientId: string) {
    setQuestionItems((current) =>
      current[0]?.clientId === clientId ? current : current.filter((item) => item.clientId !== clientId)
    );
  }

  const normalizedCells = questionItems.map((item) => item.targetCell.trim().toUpperCase());
  const hasDuplicateCells = new Set(normalizedCells.filter(Boolean)).size
    !== normalizedCells.filter(Boolean).length;
  const singleCellPattern = /^(?:'[^']+'|[^'!:]+)?!?[A-Z]{1,3}[1-9][0-9]{0,6}$/;
  const isQuestionPackageValid = Boolean(
    questionPackageTitle.trim()
    && selectedSheetConfigId
    && questionItems.length > 0
    && questionItems.every((item) =>
      item.prompt.trim() && singleCellPattern.test(item.targetCell.trim())
    )
    && !hasDuplicateCells
  );

  return (
    <section className="grid-layout">
      <div className="panel hero-panel">
        <h2>管理主控台面板</h2>
        <p className="muted">
          目前登入管理者：{stored?.displayName ?? "教師"}。可建立課堂、選擇既有課堂、配置 Sheet、綁定題目與維護學生答案。
        </p>
        <div className="classroom-create-form">
          <input value={classroomName} onChange={(event) => setClassroomName(event.target.value)} placeholder="課堂名稱" />
        </div>
        <div className="classroom-resume-form">
          <select
            aria-label="選擇現有課堂"
            value={resumeClassroomCode}
            onChange={(event) => setResumeClassroomCode(event.target.value)}
          >
            <option value="">請選擇現有課堂</option>
            {teacherClassrooms.map((classroom) => (
              <option key={classroom.id} value={classroom.code}>
                {classroom.name}（{classroom.code}）
              </option>
            ))}
          </select>
        </div>
        <div className="status-strip">
          <span>課堂代碼：{classroomCode || "尚未建立"}</span>
          <span>即時連線：{formatRealtimeStatus(realtime.status)}</span>
        </div>
        <div className="classroom-action-row">
          <button className="hero-action-button hero-action-button-secondary" disabled={!teacherToken} onClick={() => void handleCreateClassroom()}>建立課堂</button>
          <button className="hero-action-button hero-action-button-secondary" disabled={!teacherToken || !resumeClassroomCode} onClick={() => void handleResumeClassroom()}>重返課堂</button>
          <button className="hero-action-button danger-button" disabled={!teacherToken || !resumeClassroomCode} onClick={() => void handleDeleteClassroom()}>刪除課堂</button>
        </div>
        <p className="notice">{notice}</p>
      </div>

      <div className="panel">
        <h3>雲端表單配置</h3>
        <div className="stack-form">
          <div className="inline-form">
            <input value={sheetConfigName} onChange={(event) => setSheetConfigName(event.target.value)} placeholder="配置名稱" />
            <input value={sheetIdInput} onChange={(event) => setSheetIdInput(event.target.value)} placeholder="Google Sheet ID" />
            <button disabled={!teacherToken} onClick={() => void handleCreateSheetConfig()}>確認新增</button>
          </div>
          <div className="card-list">
            {sheetConfigs.map((config) => (
              <article key={config.id} className="mini-card">
                <div className="mini-card__head">
                  <strong>{config.name}</strong>
                  <span>{config.sheetId}</span>
                </div>
                <div className="action-row">
                  <button type="button" className="secondary-button" onClick={() => void handleDeleteSheetConfig(config.id)}>刪除</button>
                </div>
              </article>
            ))}
          </div>
        </div>
      </div>

      <div className="panel">
        <h3>搶答成功組別統計</h3>
        <p className="muted">從所選 Google Sheet 的所有工作表讀取答案，依答案結尾的 _G組號 統計成功次數。</p>
        <div className="inline-form sheet-statistics-controls">
          <select
            aria-label="統計用表單配置"
            value={statisticsSheetConfigId}
            onChange={(event) => {
              setStatisticsSheetConfigId(event.target.value);
              setSheetStatistics(null);
            }}
          >
            <option value="">請選擇表單配置</option>
            {sheetConfigs.map((config) => (
              <option key={config.id} value={config.id}>{config.name}</option>
            ))}
          </select>
          <button
            type="button"
            disabled={!teacherToken || !statisticsSheetConfigId || statisticsLoading}
            onClick={() => void handleCalculateSheetStatistics()}
          >
            {statisticsLoading ? "統計中…" : "統計分數"}
          </button>
        </div>
        {sheetStatistics ? (
          <>
            <div className="stat-grid sheet-statistics-summary">
              <div><span>成功組別</span><strong>{sheetStatistics.successfulGroupCount}</strong></div>
              <div><span>成功答案數</span><strong>{sheetStatistics.totalSuccessfulAnswers}</strong></div>
            </div>
            {sheetStatistics.groups.length > 0 ? (
              <ul className="group-score-list">
                {sheetStatistics.groups.map((group) => (
                  <li key={group.groupNumber}>
                    <span>第 {group.groupNumber} 組</span>
                    <strong>{group.successfulAnswerCount} 次</strong>
                  </li>
                ))}
              </ul>
            ) : (
              <p className="muted">目前表單內沒有搶答成功的組別。</p>
            )}
          </>
        ) : null}
      </div>

      <div className="panel">
        <h3>題目與儲存格綁定</h3>
        <section className="generated-question-panel">
          <h3>自動生成題目</h3>
          <p className="muted">偵測來源工作表中的 [[Q1]]、[[Q1-1]] 標記，答案固定寫入所選工作表的相同儲存格位置。</p>
          <div className="generated-sheet-selectors">
            <fieldset><legend>偵測來源</legend>
              <label>Google Sheet<select value={sourceSheetConfigId} onChange={(event) => setSourceSheetConfigId(event.target.value)}>
                <option value="">請選擇 Google Sheet</option>{sheetConfigs.map((config) => <option key={config.id} value={config.id}>{config.name}</option>)}
              </select></label>
              <label>工作表<select value={sourceWorksheet} onChange={(event) => { setSourceWorksheet(event.target.value); setGeneratedScan(null); }}>
                <option value="">請選擇工作表</option>{sourceWorksheets.map((name) => <option key={name} value={name}>{name}</option>)}
              </select></label>
            </fieldset>
            <fieldset><legend>答案寫入位置</legend>
              <label>Google Sheet<select value={answerSheetConfigId} onChange={(event) => setAnswerSheetConfigId(event.target.value)}>
                <option value="">請選擇 Google Sheet</option>{sheetConfigs.map((config) => <option key={config.id} value={config.id}>{config.name}</option>)}
              </select></label>
              <label>工作表<select value={answerWorksheet} onChange={(event) => { setAnswerWorksheet(event.target.value); setGeneratedScan(null); }}>
                <option value="">請選擇工作表</option>{answerWorksheets.map((name) => <option key={name} value={name}>{name}</option>)}
              </select></label>
            </fieldset>
          </div>
          <button disabled={!teacherToken || !classroomCode || !sourceWorksheet || !answerWorksheet || scanLoading}
            onClick={() => void handleScanGeneratedQuestions()}>{scanLoading ? "偵測中…" : "自動偵測生成題目"}</button>
          {sheetAccessError ? <p className="notice generated-sheet-error">{sheetAccessError}</p> : null}
          {generatedScan ? <div className="generated-preview">
            {generatedScan.errors.map((error) => <p className="notice" key={error}>{error}</p>)}
            {generatedScan.warnings.map((warning) => <p className="muted" key={warning}>{warning}</p>)}
            {generatedScan.packages.map((generatedPackage) => <fieldset key={generatedPackage.code} className="question-item-editor">
              <legend>{generatedPackage.code}</legend>
              <label className="generated-package-check"><input type="checkbox" checked={generatedPackage.selected}
                onChange={(event) => updateGeneratedPackage(generatedPackage.code, "selected", event.target.checked)} />建立此題組</label>
              <label>題組敘述<input value={generatedPackage.title}
                onChange={(event) => updateGeneratedPackage(generatedPackage.code, "title", event.target.value)} /></label>
              {generatedPackage.items.map((item, index) => <div className="generated-preview-item" key={item.sourceCell}>
                <label>子題 {index + 1} 敘述<input value={item.prompt}
                  onChange={(event) => updateGeneratedItem(generatedPackage.code, item.sourceCell, event.target.value)} /></label>
                <span className="muted">來源 {sourceWorksheet}!{item.sourceCell} → 寫入 {answerWorksheet}!{item.targetCell}</span>
                {item.conflicts.map((conflict) => <span className="notice" key={conflict}>{conflict}</span>)}
              </div>)}
            </fieldset>)}
            <div className="action-row"><button type="button" className="secondary-button" onClick={() => setGeneratedScan(null)}>取消</button>
              <button disabled={generatedScan.errors.length > 0 || !generatedScan.packages.some((item) => item.selected)}
                onClick={() => void handleConfirmGeneratedQuestions()}>確認建立</button></div>
          </div> : null}
        </section>
        <div className="stack-form question-package-form">
          <label>
            選擇表單配置
            <select value={selectedSheetConfigId} onChange={(event) => setSelectedSheetConfigId(event.target.value)}>
              <option value="">請選擇表單配置</option>
              {sheetConfigs.map((config) => (
                <option key={config.id} value={config.id}>{config.name}</option>
              ))}
            </select>
          </label>
          <label>
            題目標題
            <input
              value={questionPackageTitle}
              onChange={(event) => setQuestionPackageTitle(event.target.value)}
              placeholder="題目標題"
            />
          </label>
          {questionItems.map((item, index) => (
            <fieldset className="question-item-editor" key={item.clientId}>
              <legend>子題 {index + 1}</legend>
              <label>
                題目內容
                <input
                  value={item.prompt}
                  onChange={(event) => updateQuestionItem(item.clientId, "prompt", event.target.value)}
                  placeholder="題目內容"
                />
              </label>
              <label>
                綁定儲存格
                <input
                  value={item.targetCell}
                  onChange={(event) => updateQuestionItem(item.clientId, "targetCell", event.target.value)}
                  placeholder="目標儲存格，例如 A1"
                />
              </label>
              {index > 0 ? (
                <button
                  type="button"
                  className="secondary-button"
                  onClick={() => removeQuestionItem(item.clientId)}
                >
                  移除子題
                </button>
              ) : null}
            </fieldset>
          ))}
          <button type="button" className="secondary-button" onClick={addQuestionItem}>＋新增子題</button>
          <button
            disabled={!teacherToken || !classroomCode || !isQuestionPackageValid}
            onClick={() => void handleCreateQuestion()}
          >
            建立新題目
          </button>
          <span className="muted">{formatQuestionType("BUZZ")}：每個題目包只搶答一次</span>
        </div>
        <div className="batch-action-row" aria-label="課堂搶答批次操作">
          <button disabled={!teacherToken || !classroomCode} onClick={() => void handleBatch("OPEN_ALL")}>
            開放全部搶答
          </button>
          <button disabled={!teacherToken || !classroomCode} onClick={() => void handleBatch("STOP_ALL")}>
            停止全部搶答
          </button>
          <button
            type="button"
            className="danger-button"
            disabled={!teacherToken || !classroomCode}
            onClick={() => void handleReset()}
          >
            重置全部題目
          </button>
          <button
            type="button"
            className="danger-button"
            disabled={!teacherToken || !classroomCode}
            onClick={() => void handleResetAnswerLimits()}
          >
            重置答題限制
          </button>
        </div>
        <div className="card-list">
          {questions.map((question) => {
            const questionId = String(question.id);
            const currentAnswers = answersByQuestionId[questionId] ?? [];
            const questionState = String(question.state);
            const buzzStatus = question.buzzStatus ?? (questionState === "READY" ? "NOT_OPEN" : "");
            const canOpen = questionState === "READY";
            const canComplete = questionState === "BUZZ_OPEN" || questionState === "ANSWER_OPEN" || questionState === "LOCKED";
            const canReset = questionState === "BUZZ_OPEN" || questionState === "ANSWER_OPEN" || questionState === "LOCKED";
            return (
            <article key={String(question.id)} className={`mini-card question-card question-card--${buzzStatus.toLowerCase()}`}>
              <div className="mini-card__head">
                  <strong>{String(question.title ?? question.prompt)}</strong>
                <span className={`teacher-status-badge teacher-status-badge--${buzzStatus.toLowerCase()}`}>
                  {buzzStatusLabels[buzzStatus] ?? formatQuestionState(questionState)}
                </span>
              </div>
              {editingPackageId === questionId ? (
                <div className="stack-form package-description-editor">
                  <label>題組敘述<input value={editingPackageTitle}
                    onChange={(event) => setEditingPackageTitle(event.target.value)} /></label>
                  {(question.items ?? []).map((item, index) => <label key={item.id}>子題 {index + 1} 敘述
                    <input value={editingPackageItems[item.id] ?? ""} onChange={(event) =>
                      setEditingPackageItems((current) => ({ ...current, [item.id]: event.target.value }))} />
                  </label>)}
                  <div className="action-row"><button onClick={() => void handleSavePackageEdit(question)}>儲存</button>
                    <button type="button" className="secondary-button" onClick={() => setEditingPackageId("")}>取消</button></div>
                </div>
              ) : questionState === "READY" ? (
                <button type="button" className="secondary-button" onClick={() => beginPackageEdit(question)}>編輯</button>
              ) : null}
              {question.items?.length ? (
                <ol className="question-item-list">
                  {question.items.map((item) => <li key={item.id}>{item.prompt} → {item.targetCell}</li>)}
                </ol>
              ) : null}
              {question.winnerDisplayName ? (
                <p className="question-winner">搶答者：<strong>{question.winnerDisplayName}</strong></p>
              ) : null}
              {currentAnswers.map((submission) => (
                <div key={submission.submissionId} className="submitted-answer">
                  <strong>{formatTeacherAnswerAttribution(submission.displayName, submission.groupNumber, submission.enteredByTeacher, submission.groupChallenged)}</strong>
                  {editingSubmissionId === submission.submissionId ? (
                    <div className="stack-form">
                      <label>組別<input type="number" min="1" value={editingGroupNumber} onChange={(event) => setEditingGroupNumber(event.target.value)} /></label>
                      {question.items?.length ? question.items.map((item) => (
                        <label key={item.id}>{item.prompt}
                          <input
                            value={parsePackageAnswers(editingAnswer)?.[item.id] ?? ""}
                            onChange={(event) => updateEditingPackageAnswer(item.id, event.target.value)}
                          />
                        </label>
                      )) : <textarea value={editingAnswer} onChange={(event) => setEditingAnswer(event.target.value)} />}
                      <div className="action-row">
                        <button onClick={() => void handleUpdateAnswer()}>儲存修改</button>
                        <button type="button" className="secondary-button" onClick={() => { setEditingSubmissionId(""); setEditingAnswer(""); setEditingGroupNumber(""); }}>取消</button>
                      </div>
                    </div>
                  ) : (
                    <>
                      <span>提交答案：{formatPackageAnswer(submission.answer, question.items ?? [])}</span>
                      <span className="teacher-status-badge">✓ 已作答</span>
                      <button type="button" className="secondary-button" onClick={() => {
                        setEditingSubmissionId(submission.submissionId);
                        setEditingAnswer(submission.answer);
                        setEditingGroupNumber(String(submission.groupNumber || ""));
                      }}>修改答案</button>
                      {retrySheetSubmissionId === submission.submissionId ? (
                        <button type="button" className="secondary-button" onClick={() => void handleRetrySheetSync(submission.submissionId)}>重試 Google Sheets 同步</button>
                      ) : null}
                    </>
                  )}
                </div>
              ))}
              {questionState === "LOCKED" && currentAnswers.length === 0 ? (
                manualQuestionId === questionId ? (
                  <div className="submitted-answer stack-form">
                    <label>組別<input type="number" min="1" value={editingGroupNumber} onChange={(event) => setEditingGroupNumber(event.target.value)} /></label>
                    {question.items?.length ? question.items.map((item) => (
                      <label key={item.id}>{item.prompt}
                        <input value={parsePackageAnswers(editingAnswer)?.[item.id] ?? ""} onChange={(event) => updateEditingPackageAnswer(item.id, event.target.value)} />
                      </label>
                    )) : <textarea value={editingAnswer} onChange={(event) => setEditingAnswer(event.target.value)} />}
                    <div className="action-row">
                      <button onClick={() => void handleCreateManualAnswer(questionId)}>儲存答案</button>
                      <button type="button" className="secondary-button" onClick={() => { setManualQuestionId(""); setEditingAnswer(""); setEditingGroupNumber(""); }}>取消</button>
                    </div>
                  </div>
                ) : <button type="button" className="secondary-button" onClick={() => { setManualQuestionId(questionId); setEditingAnswer(question.items?.length ? "{}" : ""); setEditingGroupNumber(""); }}>修改答案</button>
              ) : null}
              <div className="action-row">
                <button disabled={!canOpen} onClick={() => void handleStateChange(String(question.id), "BUZZ_OPEN")}>開放搶答</button>
                {String(question.type) !== "BUZZ" ? (
                  <button onClick={() => void handleStateChange(String(question.id), "ANSWER_OPEN")}>開放作答</button>
                ) : null}
                <button disabled={!canComplete} onClick={() => void handleCompleteAndReview(String(question.id))}>完成搶答並檢討</button>
                <button type="button" disabled={!canReset} className="secondary-button" onClick={() => void handleReset(String(question.id))}>重置本題</button>
                <button type="button" className="secondary-button" onClick={() => void handleDeleteQuestion(String(question.id))}>刪除題目</button>
              </div>
            </article>
          );})}
        </div>
      </div>

      <div className="panel">
        <h3>即時統計</h3>
        <div className="stat-grid">
          <div><span>參與人數</span><strong>{String(summary?.participantCount ?? 0)}</strong></div>
          <div><span>題目數</span><strong>{String(summary?.questionCount ?? 0)}</strong></div>
          <div><span>作答次數</span><strong>{String(summary?.submissionCount ?? 0)}</strong></div>
          <div><span>搶答次數</span><strong>{String(summary?.buzzCount ?? 0)}</strong></div>
        </div>
      </div>

      <div className="panel">
        <h3>在線狀態與事件流</h3>
        <ul className="presence-list">
          {members.map((member) => (
            <li key={String(member.id)}>
              <span>{String(member.userId)}</span>
              <span>{formatPresenceStatus(String(member.presenceStatus))}</span>
              <span>
                第{String(member.groupNumber || "-")}組今日成功搶答：{String(member.groupSuccessfulBuzzCount ?? 0)} / {classroomSuccessfulBuzzLimit > 0 ? classroomSuccessfulBuzzLimit : "不限"}
              </span>
            </li>
          ))}
        </ul>
        <div className="feed">
          {(realtime.events as RealtimeEventView[]).slice(-8).reverse().map((event, index) => (
            <div key={`${event.occurredAt}-${index}`} className="feed-item">
              <strong>{formatRealtimeEventType(event.type)}</strong>
              <span>{event.occurredAt}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
