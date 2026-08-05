import { useEffect, useMemo, useRef, useState } from "react";
import { type QuestionItemView, type SubmittedAnswerView, cancelBuzz, getCurrentAnswers, listQuestions, submitBuzz, submitPackageAnswers } from "../lib/api";
import { clearSession, readSession } from "../lib/session";
import { formatQuestionState, formatRealtimeStatus } from "../lib/text";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";
import { formatPackageAnswer } from "../lib/questionPackageAnswer";
import {
  buzzInteractionKey,
  canCancelBuzz,
  mergeSubmittedInteractionKeys,
  ownSubmittedInteractionKeys,
  realtimeEventApplies,
  removeQuestionRoundKeys
} from "./buzzRoundState";

type RealtimeEventView = {
  type: string;
  occurredAt: string;
  payload?: {
    userId?: string;
    externalUserId?: string;
    displayName?: string;
    questionId?: string;
    packageId?: string;
    successful?: boolean;
    roundId?: string;
  };
};

export function StudentPlayPage() {
  const session = readSession();
  const [questions, setQuestions] = useState<Array<Record<string, unknown>>>([]);
  const [answersByQuestionId, setAnswersByQuestionId] = useState<Record<string, Record<string, string>>>({});
  const [currentAnswersByQuestionId, setCurrentAnswersByQuestionId] = useState<Record<string, SubmittedAnswerView[]>>({});
  const [locallyWonQuestionIds, setLocallyWonQuestionIds] = useState<Record<string, boolean>>({});
  const [submittedQuestionIds, setSubmittedQuestionIds] = useState<string[]>([]);
  const [submittingQuestionIds, setSubmittingQuestionIds] = useState<string[]>([]);
  const submittingKeysRef = useRef(new Set<string>());
  const [noticeByQuestionId, setNoticeByQuestionId] = useState<Record<string, string>>({});
  const [notice, setNotice] = useState("請等待教師開放搶答。");
  const realtime = useRealtimeFeed(session?.token ?? null, session?.classroomCode ?? null);
  const currentRoundByQuestionId = useMemo(() => Object.fromEntries(
    questions.map((question) => [String(question.id), String(question.roundId ?? "0")])
  ), [questions]);

  const activeQuestions = useMemo(() => {
    return questions.filter((question) => {
      const state = String(question.state);
      return state === "ANSWER_OPEN" || state === "BUZZ_OPEN" || state === "PUBLISHED" || state === "LOCKED";
    });
  }, [questions]);

  const buzzWinnerByQuestionId = useMemo(() => {
    const winners: Record<string, string> = {};
    for (const event of realtime.events as RealtimeEventView[]) {
      const questionId = event.payload?.packageId ?? event.payload?.questionId;
      if (!questionId) {
        continue;
      }
      const eventRoundId = String(event.payload?.roundId ?? "0");
      const currentRoundId = currentRoundByQuestionId[questionId] ?? "0";
      if (eventRoundId !== currentRoundId) {
        continue;
      }
      if (event.type === "BUZZ_RESULT" && event.payload?.successful) {
        winners[questionId] = String(event.payload.externalUserId ?? event.payload.userId ?? "");
      }
      if (event.type === "BUZZ_CANCELLED" || event.type === "BUZZ_RESET") {
        delete winners[questionId];
      }
    }
    return winners;
  }, [realtime.events, currentRoundByQuestionId]);

  useEffect(() => {
    const latestEvent = (realtime.events as RealtimeEventView[]).at(-1);
    const questionId = latestEvent?.payload?.packageId ?? latestEvent?.payload?.questionId;
    if (!latestEvent || !questionId) {
      return;
    }
    const eventRoundId = String(latestEvent.payload?.roundId ?? "0");
    const currentRoundId = currentRoundByQuestionId[questionId] ?? "0";
    if (!realtimeEventApplies(latestEvent.type, eventRoundId, currentRoundId)) {
      return;
    }
    if (latestEvent.type === "BUZZ_CANCELLED" || latestEvent.type === "BUZZ_RESET") {
      const interactionKey = buzzInteractionKey(questionId, eventRoundId);
      setLocallyWonQuestionIds((current) => {
        const next = { ...current };
        delete next[interactionKey];
        return next;
      });
      setAnswersByQuestionId((current) => {
        const next = { ...current };
        delete next[interactionKey];
        return next;
      });
      if (latestEvent.type === "BUZZ_RESET") {
        setSubmittedQuestionIds((current) => removeQuestionRoundKeys(current, questionId));
      }
    }
  }, [realtime.events, currentRoundByQuestionId]);

  useEffect(() => {
    if (!session?.token || !session.classroomCode) {
      return;
    }
    void listQuestions(session.token, session.classroomCode).then(async (result) => {
      const loadedQuestions = result.questions as unknown as Array<Record<string, unknown>>;
      setQuestions(loadedQuestions);
      const answerResults = await Promise.all(loadedQuestions.map((question) =>
        getCurrentAnswers(session.token, session.classroomCode!, String(question.id))
      ));
      setCurrentAnswersByQuestionId(Object.fromEntries(
        answerResults.map((answerResult) => [answerResult.questionId, answerResult.answers])
      ));

      const answersByQuestionId = Object.fromEntries(
        answerResults.map((answerResult) => [answerResult.questionId, answerResult.answers])
      );
      const ownSubmittedKeys = ownSubmittedInteractionKeys(loadedQuestions, answersByQuestionId);
      setSubmittedQuestionIds((current) => mergeSubmittedInteractionKeys(current, ownSubmittedKeys));
    });
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

  async function handleSubmitAnswer(questionId: string, interactionKey: string, items: QuestionItemView[]) {
    if (submittedQuestionIds.includes(interactionKey) || submittingKeysRef.current.has(interactionKey)) return;
    submittingKeysRef.current.add(interactionKey);
    setSubmittingQuestionIds((current) => [...current, interactionKey]);
    try {
      await submitPackageAnswers(
        activeSession.token,
        activeSession.classroomCode!,
        questionId,
        items.map((item) => ({
          itemId: item.id,
          answer: answersByQuestionId[interactionKey]?.[item.id] ?? ""
        }))
      );
      setSubmittedQuestionIds((current) => mergeSubmittedInteractionKeys(current, [interactionKey]));
      setLocallyWonQuestionIds((current) => {
        const next = { ...current };
        delete next[interactionKey];
        return next;
      });
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: "✓ 已作答"
      }));
    } catch (error) {
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: error instanceof Error ? error.message : "答案送出失敗。"
      }));
    } finally {
      submittingKeysRef.current.delete(interactionKey);
      setSubmittingQuestionIds((current) => current.filter((key) => key !== interactionKey));
    }
  }

  async function handleBuzz(questionId: string, interactionKey: string) {
    try {
      const result = await submitBuzz(activeSession.token, activeSession.classroomCode!, questionId);
      if (result.accepted) {
        setLocallyWonQuestionIds((current) => ({ ...current, [interactionKey]: true }));
      }
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: result.accepted ? "你搶答成功，請輸入答案。" : "你未搶到答題權。"
      }));
    } catch (error) {
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: error instanceof Error ? error.message : "搶答失敗。"
      }));
    }
  }

  async function handleCancelLock(questionId: string, interactionKey: string) {
    try {
      await cancelBuzz(activeSession.token, activeSession.classroomCode!, questionId);
      setLocallyWonQuestionIds((current) => {
        const next = { ...current };
        delete next[interactionKey];
        return next;
      });
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: "已取消鎖定，所有人重新獲得競爭權。"
      }));
    } catch (error) {
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: error instanceof Error ? error.message : "取消鎖定失敗。"
      }));
    }
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
        {activeQuestions.length > 0 ? (
          <div className="question-list">
            {activeQuestions.map((question) => {
              const questionId = String(question.id);
              const roundId = String(question.roundId ?? "0");
              const interactionKey = buzzInteractionKey(questionId, roundId);
              const state = String(question.state);
              const winnerUserId = buzzWinnerByQuestionId[questionId] ?? "";
              const isWinner = locallyWonQuestionIds[interactionKey] === true
                || (winnerUserId !== "" && winnerUserId === currentUserId);
              const isLoser = winnerUserId !== "" && winnerUserId !== currentUserId;
              const canBuzz = state === "BUZZ_OPEN" && winnerUserId === "";
              const canAnswer = isWinner || state === "ANSWER_OPEN";
              const currentAnswers = currentAnswersByQuestionId[questionId] ?? [];
              const hasSubmitted = submittedQuestionIds.includes(interactionKey)
                || currentAnswers.some((submission) => submission.userId === currentUserId);
              const isSubmitting = submittingQuestionIds.includes(interactionKey);
              const packageAnswers = answersByQuestionId[interactionKey] ?? {};
              const items = (question.items ?? []) as QuestionItemView[];

              return (
                <article className="question-stage" key={questionId}>
                  <strong>{String(question.title ?? question.prompt)}</strong>
                  <ol className="question-item-list">
                    {items.map((item) => <li key={item.id}>{item.prompt}</li>)}
                  </ol>
                  <span className={`pill ${canBuzz ? "pill-open" : isWinner ? "pill-won" : isLoser ? "pill-lost" : ""}`}>
                    {formatQuestionState(state)}
                  </span>
                  {winnerUserId ? <p className="muted">目前搶答成功者：{winnerUserId}</p> : null}
                  {noticeByQuestionId[questionId] ? <p className="notice">{noticeByQuestionId[questionId]}</p> : null}
                  {currentAnswers.map((submission) => (
                    <div key={submission.submissionId} className="submitted-answer">
                      <strong>{submission.displayName}</strong>
                      <span>提交答案：{formatPackageAnswer(submission.answer, items)}</span>
                      <span className="pill pill-won">✓ 已作答</span>
                    </div>
                  ))}
                  {state === "BUZZ_OPEN" ? (
                    <>
                      <button
                        className={`buzz-button ${canBuzz ? "buzz-button-open" : isWinner ? "buzz-button-won" : "buzz-button-locked"}`}
                        disabled={!canBuzz}
                        onClick={() => void handleBuzz(questionId, interactionKey)}
                      >
                        {canBuzz ? "搶答" : isWinner ? "已搶答" : isLoser ? "已被搶答" : "未開放"}
                      </button>
                      {isWinner ? (
                        <>
                          {items.map((item) => (
                            <label key={item.id}>
                              {item.prompt}
                              <textarea
                                disabled={hasSubmitted || isSubmitting}
                                value={packageAnswers[item.id] ?? ""}
                                onChange={(event) => setAnswersByQuestionId((current) => ({
                                  ...current,
                                  [interactionKey]: {
                                    ...(current[interactionKey] ?? {}),
                                    [item.id]: event.target.value
                                  }
                                }))}
                                placeholder="請輸入答案"
                              />
                            </label>
                          ))}
                          <div className="action-row">
                            <button disabled={hasSubmitted || isSubmitting} onClick={() => void handleSubmitAnswer(questionId, interactionKey, items)}>
                              提交答案
                            </button>
                            {canCancelBuzz(isWinner, hasSubmitted) && !isSubmitting ? (
                              <button type="button" className="secondary-button" onClick={() => void handleCancelLock(questionId, interactionKey)}>
                                取消鎖定
                              </button>
                            ) : null}
                          </div>
                        </>
                      ) : null}
                    </>
                  ) : canAnswer ? (
                    <>
                      {items.map((item) => (
                        <label key={item.id}>
                          {item.prompt}
                          <textarea
                            disabled={hasSubmitted || isSubmitting}
                            value={packageAnswers[item.id] ?? ""}
                            onChange={(event) => setAnswersByQuestionId((current) => ({
                              ...current,
                              [interactionKey]: {
                                ...(current[interactionKey] ?? {}),
                                [item.id]: event.target.value
                              }
                            }))}
                            placeholder="請輸入答案"
                          />
                        </label>
                      ))}
                      <button disabled={hasSubmitted || isSubmitting} onClick={() => void handleSubmitAnswer(questionId, interactionKey, items)}>
                        提交答案
                      </button>
                    </>
                  ) : (
                    <button className="buzz-button buzz-button-locked" disabled>未開放</button>
                  )}
                </article>
              );
            })}
          </div>
        ) : (
          <div className="question-stage">
            <span className="pill">未開放</span>
            <button className="buzz-button buzz-button-locked" disabled>未開放</button>
          </div>
        )}
      </div>

    </section>
  );
}
