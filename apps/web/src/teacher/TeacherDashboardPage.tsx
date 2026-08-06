import { useEffect, useMemo, useState } from "react";
import {
  type QuestionView,
  type SubmittedAnswerView,
  batchQuestionAction,
  createClassroom,
  createQuestionPackage,
  createSheetConfig,
  deleteSheetConfig,
  getCurrentAnswers,
  getMembers,
  getSummary,
  joinClassroom,
  listQuestions,
  listSheetConfigs,
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
import { formatPackageAnswer } from "../lib/questionPackageAnswer";

type RealtimeEventView = {
  type: string;
  occurredAt: string;
};

type SubmissionView = {
  submissionId: string;
  userId: string;
  displayName: string;
  answer: string;
  reviewStatus: string;
  submittedAt: string;
};

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";
const defaultSettings = "{\"buzzBonus\":5,\"wrongAnswerPenalty\":2,\"showAnswer\":false}";

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
  const [resumeClassroomCode, setResumeClassroomCode] = useState(stored?.classroomCode ?? "");
  const [classroomName, setClassroomName] = useState("課堂 A");
  const [questionPackageTitle, setQuestionPackageTitle] = useState("");
  const [answerTimeLimitSeconds, setAnswerTimeLimitSeconds] = useState("30");
  const [questionItems, setQuestionItems] = useState<Array<{ clientId: string; prompt: string; targetCell: string }>>([
    { clientId: crypto.randomUUID(), prompt: "", targetCell: "" }
  ]);
  const [sheetConfigs, setSheetConfigs] = useState<Array<{ id: string; name: string; sheetId: string }>>([]);
  const [sheetConfigName, setSheetConfigName] = useState("");
  const [sheetIdInput, setSheetIdInput] = useState("");
  const [selectedSheetConfigId, setSelectedSheetConfigId] = useState("");
  const [questions, setQuestions] = useState<QuestionView[]>([]);
  const [answersByQuestionId, setAnswersByQuestionId] = useState<Record<string, SubmittedAnswerView[]>>({});
  const [selectedQuestionId, setSelectedQuestionId] = useState("");
  const [submissions, setSubmissions] = useState<SubmissionView[]>([]);
  const [editingSubmissionId, setEditingSubmissionId] = useState("");
  const [editingAnswer, setEditingAnswer] = useState("");
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
  }, [teacherToken]);

  useEffect(() => {
    if (!teacherToken || !classroomCode) {
      return;
    }
    void Promise.all([
      listQuestions(teacherToken, classroomCode).then((result) => setQuestions(result.questions)),
      getMembers(teacherToken, classroomCode).then((result) => setMembers(result.members as unknown as Array<Record<string, unknown>>)),
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

  useEffect(() => {
    if (!teacherToken || !classroomCode || !selectedQuestionId) {
      setSubmissions([]);
      return;
    }
    void authorizedGet<{ submissions: SubmissionView[] }>(
      `/api/reports/submissions?classroomCode=${encodeURIComponent(classroomCode)}&questionId=${encodeURIComponent(selectedQuestionId)}`,
      teacherToken
    ).then((result) => setSubmissions(result.submissions));
  }, [teacherToken, classroomCode, selectedQuestionId, lastRealtimeEvent]);

  async function handleCreateClassroom() {
    if (!teacherToken || !stored) {
      return;
    }
    const result = await createClassroom(teacherToken, classroomName, defaultSettings);
    setClassroomCode(result.classroom.code);
    setResumeClassroomCode(result.classroom.code);
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
    setNotice("Google Sheet 配置已刪除。");
  }

  async function handleCreateQuestion() {
    if (!teacherToken || !classroomCode) {
      return;
    }
    const configuredAnswerTimeLimitSeconds = Number(answerTimeLimitSeconds);
    if (!Number.isInteger(configuredAnswerTimeLimitSeconds) || configuredAnswerTimeLimitSeconds <= 0) {
      setNotice("作答時限必須是正整數秒數。");
      return;
    }
    const result = await createQuestionPackage(teacherToken, classroomCode, {
      title: questionPackageTitle,
      sheetConfigId: selectedSheetConfigId,
      items: questionItems.map(({ prompt, targetCell }) => ({ prompt, targetCell })),
      score: 10,
      answerTimeLimitSeconds: configuredAnswerTimeLimitSeconds
    });
    setQuestions((current) => [...current, result.package]);
    setSelectedQuestionId(String(result.package.id));
    setQuestionPackageTitle("");
    setAnswerTimeLimitSeconds("30");
    setQuestionItems([{ clientId: crypto.randomUUID(), prompt: "", targetCell: "" }]);
    setNotice("題目已建立。");
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
    setSelectedQuestionId(questionId);
    setNotice("搶答已完成，請在下方檢討並修改學生答案。");
  }

  async function handleUpdateAnswer() {
    if (!teacherToken || !editingSubmissionId) {
      return;
    }
    await authorizedPost("/api/submissions/answer", teacherToken, {
      submissionId: editingSubmissionId,
      answer: editingAnswer
    });
    setSubmissions((current) =>
      current.map((submission) =>
        submission.submissionId === editingSubmissionId
          ? { ...submission, answer: editingAnswer }
          : submission
      )
    );
    setEditingSubmissionId("");
    setEditingAnswer("");
    setNotice("學生答案已更新，且已重新同步到 Google Sheets。");
  }

  async function handleDeleteAnswer(submissionId: string) {
    if (!teacherToken) return;
    await authorizedPost("/api/submissions/delete", teacherToken, { submissionId });
    setSubmissions((current) => current.filter((submission) => submission.submissionId !== submissionId));
    setNotice("學生答案已刪除。");
  }

  async function handleDeleteQuestion(questionId: string) {
    if (!teacherToken || !classroomCode) return;
    await authorizedPost("/api/questions/delete", teacherToken, { classroomCode, questionId });
    setQuestions((current) => current.filter((question) => String(question.id) !== questionId));
    if (selectedQuestionId === questionId) {
      setSelectedQuestionId("");
      setSubmissions([]);
    }
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
    && Number.isInteger(Number(answerTimeLimitSeconds))
    && Number(answerTimeLimitSeconds) > 0
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
          目前登入管理者：{stored?.displayName ?? "教師"}。可建立課堂、輸入課堂代碼重返既有課堂、配置 Sheet、綁定題目與維護學生答案。
        </p>
        <div className="inline-form">
          <input value={classroomName} onChange={(event) => setClassroomName(event.target.value)} placeholder="課堂名稱" />
          <button disabled={!teacherToken} onClick={() => void handleCreateClassroom()}>建立課堂</button>
        </div>
        <div className="inline-form">
          <input
            value={resumeClassroomCode}
            onChange={(event) => setResumeClassroomCode(event.target.value.toUpperCase())}
            placeholder="輸入課堂代碼以重返既有課堂"
          />
          <button disabled={!teacherToken} onClick={() => void handleResumeClassroom()}>重返課堂</button>
        </div>
        <div className="status-strip">
          <span>課堂代碼：{classroomCode || "尚未建立"}</span>
          <span>即時連線：{formatRealtimeStatus(realtime.status)}</span>
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
        <h3>題目與儲存格綁定</h3>
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
          <label>
            作答時限（秒）
            <input
              type="number"
              min="1"
              step="1"
              inputMode="numeric"
              value={answerTimeLimitSeconds}
              onChange={(event) => setAnswerTimeLimitSeconds(event.target.value)}
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
                  <strong>{submission.displayName}</strong>
                      <span>提交答案：{formatPackageAnswer(submission.answer, question.items ?? [])}</span>
                  <span className="teacher-status-badge">✓ 已作答</span>
                </div>
              ))}
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
        <h3>搶答檢討與學生答案修改</h3>
        <p className="muted">{selectedQuestionId ? `目前題目 ID：${selectedQuestionId}` : "學生送出答案後，請按「完成搶答並檢討」。"}</p>
        <div className="card-list">
          {submissions.map((submission) => (
            <article key={submission.submissionId} className="mini-card">
              <div className="mini-card__head">
                <strong>{submission.displayName}</strong>
              </div>
              {editingSubmissionId === submission.submissionId ? (
                <div className="stack-form">
                  <textarea value={editingAnswer} onChange={(event) => setEditingAnswer(event.target.value)} />
                  <div className="action-row">
                    <button onClick={() => void handleUpdateAnswer()}>儲存修改</button>
                    <button type="button" className="secondary-button" onClick={() => { setEditingSubmissionId(""); setEditingAnswer(""); }}>取消</button>
                  </div>
                </div>
              ) : (
                <div className="action-row">
                  <span>{submission.answer}</span>
                  <button type="button" className="secondary-button" onClick={() => { setEditingSubmissionId(submission.submissionId); setEditingAnswer(submission.answer); }}>
                    修改答案
                  </button>
                  <button type="button" className="secondary-button" onClick={() => void handleDeleteAnswer(submission.submissionId)}>
                    刪除答案
                  </button>
                </div>
              )}
            </article>
          ))}
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
