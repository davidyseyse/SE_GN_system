import { useEffect, useMemo, useState } from "react";
import type { LeaderboardVisibility } from "@classroom/shared";
import {
  type QuestionView,
  createClassroom,
  createQuestion,
  createSheetConfig,
  deleteSheetConfig,
  getLeaderboard,
  getMembers,
  getSummary,
  listQuestions,
  listSheetConfigs,
  resetBuzz,
  updateQuestionState
} from "../lib/api";
import { readSession, writeSession } from "../lib/session";
import {
  formatLeaderboardVisibility,
  formatPresenceStatus,
  formatQuestionState,
  formatQuestionType,
  formatRealtimeEventType,
  formatRealtimeStatus
} from "../lib/text";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";

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
  const [classroomName, setClassroomName] = useState("課堂 A");
  const [leaderboardVisibility, setLeaderboardVisibility] = useState<LeaderboardVisibility>("TOP_10");
  const [questionPrompt, setQuestionPrompt] = useState("法國的首都是哪裡？");
  const [questionAnswer, setQuestionAnswer] = useState("巴黎");
  const [questionType, setQuestionType] = useState("BUZZ");
  const [sheetConfigs, setSheetConfigs] = useState<Array<{ id: string; name: string; sheetId: string }>>([]);
  const [sheetConfigName, setSheetConfigName] = useState("");
  const [sheetIdInput, setSheetIdInput] = useState("");
  const [targetCell, setTargetCell] = useState("");
  const [selectedSheetConfigId, setSelectedSheetConfigId] = useState("");
  const [questions, setQuestions] = useState<QuestionView[]>([]);
  const [selectedQuestionId, setSelectedQuestionId] = useState("");
  const [submissions, setSubmissions] = useState<SubmissionView[]>([]);
  const [editingSubmissionId, setEditingSubmissionId] = useState("");
  const [editingAnswer, setEditingAnswer] = useState("");
  const [leaderboard, setLeaderboard] = useState<Array<Record<string, unknown>>>([]);
  const [members, setMembers] = useState<Array<Record<string, unknown>>>([]);
  const [summary, setSummary] = useState<Record<string, unknown> | null>(null);
  const [notice, setNotice] = useState("請先建立課堂，然後開始配置題目與同步流程。");
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
      getLeaderboard(teacherToken, classroomCode).then((result) => setLeaderboard(result.leaderboard as unknown as Array<Record<string, unknown>>)),
      getMembers(teacherToken, classroomCode).then((result) => setMembers(result.members as unknown as Array<Record<string, unknown>>)),
      getSummary(teacherToken, classroomCode).then((result) => setSummary(result as unknown as Record<string, unknown>))
    ]);
  }, [teacherToken, classroomCode, lastRealtimeEvent]);

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
    const result = await createClassroom(teacherToken, classroomName, leaderboardVisibility, defaultSettings);
    setClassroomCode(result.classroom.code);
    writeSession({
      ...stored,
      classroomCode: result.classroom.code,
      classroomId: result.classroom.id
    });
    setNotice(`課堂 ${result.classroom.code} 已建立。`);
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
    const publicationSettings = JSON.stringify({
      showAnswer: false,
      sheetConfigId: selectedSheetConfigId,
      targetCell
    });
    const result = await createQuestion(teacherToken, classroomCode, {
      prompt: questionPrompt,
      type: questionType,
      score: 10,
      answerKey: questionAnswer,
      publicationSettings,
      answerTimeLimitSeconds: 30
    });
    setQuestions((current) => [...current, result.question]);
    setSelectedQuestionId(String(result.question.id));
    setNotice("題目已建立。");
  }

  async function handleStateChange(questionId: string, targetState: string) {
    if (!teacherToken || !classroomCode) {
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
    await resetBuzz(teacherToken, classroomCode, questionId);
    const result = await listQuestions(teacherToken, classroomCode);
    setQuestions(result.questions);
    setNotice(questionId ? "該題已重置為未開放狀態。" : "所有題目已重置為未開放狀態。");
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

  return (
    <section className="grid-layout">
      <div className="panel hero-panel">
        <h2>管理主控台面板</h2>
        <p className="muted">
          目前登入管理者：{stored?.displayName ?? "教師"}。可建立課堂、配置 Sheet、綁定題目與維護學生答案。
        </p>
        <div className="inline-form">
          <input value={classroomName} onChange={(event) => setClassroomName(event.target.value)} placeholder="課堂名稱" />
          <select value={leaderboardVisibility} onChange={(event) => setLeaderboardVisibility(event.target.value as LeaderboardVisibility)}>
            <option value="HIDDEN">{formatLeaderboardVisibility("HIDDEN")}</option>
            <option value="TOP_3">{formatLeaderboardVisibility("TOP_3")}</option>
            <option value="TOP_10">{formatLeaderboardVisibility("TOP_10")}</option>
            <option value="FULL">{formatLeaderboardVisibility("FULL")}</option>
          </select>
          <button disabled={!teacherToken} onClick={() => void handleCreateClassroom()}>建立課堂</button>
          <button type="button" className="secondary-button" disabled={!teacherToken || !classroomCode} onClick={() => void handleReset()}>
            全域重置狀態
          </button>
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
        <div className="stack-form">
          <input value={questionPrompt} onChange={(event) => setQuestionPrompt(event.target.value)} placeholder="題目內容" />
          <div className="inline-form">
            <select value={questionType} onChange={(event) => setQuestionType(event.target.value)}>
              <option value="BUZZ">{formatQuestionType("BUZZ")}</option>
              <option value="SHORT_ANSWER">{formatQuestionType("SHORT_ANSWER")}</option>
              <option value="SINGLE_CHOICE">{formatQuestionType("SINGLE_CHOICE")}</option>
              <option value="MULTIPLE_CHOICE">{formatQuestionType("MULTIPLE_CHOICE")}</option>
              <option value="TRUE_FALSE">{formatQuestionType("TRUE_FALSE")}</option>
            </select>
            <input value={questionAnswer} onChange={(event) => setQuestionAnswer(event.target.value)} placeholder="答案 / 預設內容" />
            <select value={selectedSheetConfigId} onChange={(event) => setSelectedSheetConfigId(event.target.value)}>
              <option value="">選擇表單配置</option>
              {sheetConfigs.map((config) => (
                <option key={config.id} value={config.id}>{config.name}</option>
              ))}
            </select>
            <input value={targetCell} onChange={(event) => setTargetCell(event.target.value.toUpperCase())} placeholder="目標儲存格，例如 A1" />
            <button disabled={!teacherToken || !classroomCode} onClick={() => void handleCreateQuestion()}>建立新題目</button>
          </div>
        </div>
        <div className="card-list">
          {questions.map((question) => (
            <article key={String(question.id)} className="mini-card">
              <div className="mini-card__head">
                <strong>{String(question.prompt)}</strong>
                <span>{formatQuestionState(String(question.state))}</span>
              </div>
              <div className="action-row">
                <button onClick={() => void handleStateChange(String(question.id), "READY")}>設為未開放</button>
                <button onClick={() => void handleStateChange(String(question.id), "BUZZ_OPEN")}>開放搶答</button>
                <button onClick={() => void handleStateChange(String(question.id), "ANSWER_OPEN")}>開放作答</button>
                <button onClick={() => void handleStateChange(String(question.id), "PUBLISHED")}>公布結果</button>
                <button type="button" className="secondary-button" onClick={() => void handleReset(String(question.id))}>重置本題</button>
                <button type="button" className="secondary-button" onClick={() => setSelectedQuestionId(String(question.id))}>查看答案</button>
              </div>
            </article>
          ))}
        </div>
      </div>

      <div className="panel">
        <h3>學生答案維護</h3>
        <p className="muted">{selectedQuestionId ? `目前題目 ID：${selectedQuestionId}` : "請先在上方選擇一題查看答案。"}</p>
        <div className="card-list">
          {submissions.map((submission) => (
            <article key={submission.submissionId} className="mini-card">
              <div className="mini-card__head">
                <strong>{submission.displayName}</strong>
                <span>{submission.submittedAt}</span>
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
        <h4>排行榜</h4>
        <ol className="rank-list">
          {leaderboard.map((entry) => (
            <li key={String(entry.userId)}>
              <span>{String(entry.displayName)}</span>
              <strong>{String(entry.score)}</strong>
            </li>
          ))}
        </ol>
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
