import { useEffect, useMemo, useState } from "react";
import { cancelBuzz, getLeaderboard, listQuestions, submitAnswer, submitBuzz } from "../lib/api";
import { clearSession, readSession } from "../lib/session";
import { formatQuestionState, formatRealtimeEventType, formatRealtimeStatus } from "../lib/text";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";

type RealtimeEventView = {
  type: string;
  occurredAt: string;
  payload?: {
    userId?: string;
    externalUserId?: string;
    displayName?: string;
    questionId?: string;
    successful?: boolean;
  };
};

export function StudentPlayPage() {
  const session = readSession();
  const [questions, setQuestions] = useState<Array<Record<string, unknown>>>([]);
  const [answer, setAnswer] = useState("");
  const [locallyWonQuestionId, setLocallyWonQuestionId] = useState("");
  const [submittedQuestionIds, setSubmittedQuestionIds] = useState<string[]>([]);
  const [leaderboard, setLeaderboard] = useState<Array<Record<string, unknown>>>([]);
  const [notice, setNotice] = useState("請等待教師開放搶答。");
  const realtime = useRealtimeFeed(session?.token ?? null, session?.classroomCode ?? null);

  const activeQuestion = useMemo(() => {
    const visible = questions.find((question) => {
      const state = String(question.state);
      return state === "ANSWER_OPEN" || state === "BUZZ_OPEN" || state === "PUBLISHED" || state === "LOCKED";
    });
    return visible ?? null;
  }, [questions]);

  const buzzWinnerUserId = useMemo(() => {
    const questionId = activeQuestion ? String(activeQuestion.id) : "";
    let winner = "";
    for (const event of realtime.events as RealtimeEventView[]) {
      if (event.payload?.questionId !== questionId) {
        continue;
      }
      if (event.type === "BUZZ_RESULT" && event.payload?.successful) {
        winner = String(event.payload.externalUserId ?? event.payload.userId ?? "");
      }
      if (event.type === "BUZZ_CANCELLED" || event.type === "BUZZ_RESET") {
        winner = "";
      }
    }
    return winner;
  }, [activeQuestion, realtime.events]);

  useEffect(() => {
    if (!session?.token || !session.classroomCode) {
      return;
    }
    void Promise.all([
      listQuestions(session.token, session.classroomCode).then((result) => setQuestions(result.questions as unknown as Array<Record<string, unknown>>)),
      getLeaderboard(session.token, session.classroomCode).then((result) => setLeaderboard(result.leaderboard as unknown as Array<Record<string, unknown>>))
    ]);
  }, [session?.token, session?.classroomCode, realtime.events.length]);

  if (!session?.token || !session.classroomCode) {
    return (
      <section className="center-panel">
        <div className="panel student-panel">
          <h2>尚未加入搶答大廳</h2>
          <p className="muted">請先登入學生帳號並加入課堂。</p>
          <button onClick={() => clearSession()}>清除會話</button>
        </div>
      </section>
    );
  }

  const activeSession = session;
  const currentUserId = session.userId;
  const state = activeQuestion ? String(activeQuestion.state) : "READY";
  const isBuzzOpen = state === "BUZZ_OPEN";
  const isWinner = (activeQuestion !== null && locallyWonQuestionId === String(activeQuestion.id))
    || (buzzWinnerUserId !== "" && buzzWinnerUserId === currentUserId);
  const isLoser = buzzWinnerUserId !== "" && buzzWinnerUserId !== currentUserId;
  const canBuzz = isBuzzOpen && buzzWinnerUserId === "";
  const canAnswer = isWinner || state === "ANSWER_OPEN";
  const hasSubmitted = activeQuestion !== null && submittedQuestionIds.includes(String(activeQuestion.id));

  async function handleSubmitAnswer() {
    if (!activeQuestion) {
      return;
    }
    const questionId = String(activeQuestion.id);
    if (submittedQuestionIds.includes(questionId)) return;
    try {
      const result = await submitAnswer(activeSession.token, activeSession.classroomCode!, questionId, answer);
      setSubmittedQuestionIds((current) => [...current, questionId]);
      setNotice(`作答已送出。批改狀態：${result.reviewStatus || "待教師確認"}`);
      setAnswer("");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "答案送出失敗。");
    }
  }

  async function handleBuzz() {
    if (!activeQuestion) {
      return;
    }
    const result = await submitBuzz(activeSession.token, activeSession.classroomCode!, String(activeQuestion.id));
    if (result.accepted) {
      setLocallyWonQuestionId(String(activeQuestion.id));
    }
    setNotice(result.accepted ? "你搶答成功，請輸入答案。" : "你未搶到答題權。");
  }

  async function handleCancelLock() {
    if (!activeQuestion) {
      return;
    }
    await cancelBuzz(activeSession.token, activeSession.classroomCode!, String(activeQuestion.id));
    setNotice("已取消鎖定，所有人重新獲得競爭權。");
  }

  return (
    <section className="grid-layout student-live">
      <div className="panel hero-panel">
        <h2>搶答大廳</h2>
        <p className="muted">
          學號：{session.studentId ?? session.userId} / 組別：{session.groupNumber ?? "-"} / 課堂代碼：{activeSession.classroomCode}
        </p>
        <p className="muted">即時連線狀態：{formatRealtimeStatus(realtime.status)}</p>
        <p className="notice">{notice}</p>
      </div>

      <div className="panel">
        <h3>目前題目</h3>
        {activeQuestion ? (
          <div className="question-stage">
            <strong>{String(activeQuestion.prompt)}</strong>
            <span className={`pill ${canBuzz ? "pill-open" : isWinner ? "pill-won" : isLoser ? "pill-lost" : ""}`}>
              {formatQuestionState(String(activeQuestion.state))}
            </span>
            {buzzWinnerUserId ? <p className="muted">目前搶答成功者：{buzzWinnerUserId}</p> : null}
            {String(activeQuestion.state) === "BUZZ_OPEN" ? (
              <>
                <button className={`buzz-button ${canBuzz ? "buzz-button-open" : isWinner ? "buzz-button-won" : "buzz-button-locked"}`} disabled={!canBuzz} onClick={() => void handleBuzz()}>
                  {canBuzz ? "搶答" : isWinner ? "已搶答" : isLoser ? "已被搶答" : "未開放"}
                </button>
                {isWinner ? (
                  <>
                    <textarea disabled={hasSubmitted} value={answer} onChange={(event) => setAnswer(event.target.value)} placeholder="請輸入答案" />
                    <div className="action-row">
                      <button disabled={hasSubmitted} onClick={() => void handleSubmitAnswer()}>{hasSubmitted ? "答案已提交" : "提交答案"}</button>
                      <button type="button" className="secondary-button" onClick={() => void handleCancelLock()}>取消鎖定</button>
                    </div>
                  </>
                ) : null}
              </>
            ) : canAnswer ? (
              <>
                <textarea disabled={hasSubmitted} value={answer} onChange={(event) => setAnswer(event.target.value)} placeholder="請輸入答案" />
                <button disabled={hasSubmitted} onClick={() => void handleSubmitAnswer()}>{hasSubmitted ? "答案已提交" : "提交答案"}</button>
              </>
            ) : (
              <button className="buzz-button buzz-button-locked" disabled>未開放</button>
            )}
          </div>
        ) : (
          <div className="question-stage">
            <span className="pill">未開放</span>
            <button className="buzz-button buzz-button-locked" disabled>未開放</button>
          </div>
        )}
      </div>

      <div className="panel">
        <h3>排行榜</h3>
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
        <h3>即時訊息</h3>
        <div className="feed">
          {(realtime.events as RealtimeEventView[]).slice(-10).reverse().map((event, index) => (
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
