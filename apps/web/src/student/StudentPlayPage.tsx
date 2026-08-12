import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { type QuestionItemView, type SubmittedAnswerView, cancelBuzz, getCurrentAnswers, listQuestions, submitBuzz, submitPackageAnswers } from "../lib/api";
import { createLatestRequestGate } from "../lib/latestRequestGate";
import { arePackageAnswersComplete } from "../lib/questionPackageAnswer";
import { clearSession, readSession } from "../lib/session";
import { formatQuestionState, formatRealtimeStatus } from "../lib/text";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";
import {
  buzzInteractionKey,
  canCancelBuzz,
  mergeSubmittedInteractionKeys,
  ownSubmittedInteractionKeys,
  realtimeEventApplies,
  removeInteractionEntry,
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

function submissionOutcomeMessage(outcomeStatus: string): string {
  switch (outcomeStatus) {
    case "NOT_FASTEST":
      return "已有更快的有效搶答，本次答案未寫入系統。";
    case "SUCCESS_LIMIT_REACHED":
      return "你的組別已達全系統搶答成功上限，本次答案未寫入系統。";
    case "WINNER_ALREADY_SUBMITTED":
      return "本題已有最快答案，本次答案未寫入系統。";
    default:
      return "本次答案未被系統接受。";
  }
}

export function StudentPlayPage() {
  const session = readSession();
  const [questions, setQuestions] = useState<Array<Record<string, unknown>>>([]);
  const [answersByQuestionId, setAnswersByQuestionId] = useState<Record<string, Record<string, string>>>({});
  const [currentAnswersByQuestionId, setCurrentAnswersByQuestionId] = useState<Record<string, SubmittedAnswerView[]>>({});
  const [locallyBuzzedQuestionIds, setLocallyBuzzedQuestionIds] = useState<Record<string, boolean>>({});
  const [submittedQuestionIds, setSubmittedQuestionIds] = useState<string[]>([]);
  const [submittingQuestionIds, setSubmittingQuestionIds] = useState<string[]>([]);
  const submittingKeysRef = useRef(new Set<string>());
  const answerRefreshGateRef = useRef(createLatestRequestGate());
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
    const winners: Record<string, { userId: string }> = {};
    for (const question of questions) {
      if (String(question.buzzStatus ?? "") !== "WINNER_ANSWERING") {
        continue;
      }
      const questionId = String(question.id);
      const winnerUserId = String(question.winnerExternalUserId ?? "");
      if (winnerUserId) {
        winners[questionId] = { userId: winnerUserId };
      }
    }
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
        winners[questionId] = {
          userId: String(event.payload.externalUserId ?? event.payload.userId ?? "")
        };
      }
      if (event.type === "BUZZ_CANCELLED" || event.type === "BUZZ_RESET") {
        delete winners[questionId];
      }
    }
    return winners;
  }, [questions, realtime.events, currentRoundByQuestionId]);

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
      const eventTargetsCurrentStudent = latestEvent.type === "BUZZ_RESET"
        || String(latestEvent.payload?.externalUserId ?? "") === String(session?.userId ?? "");
      if (!eventTargetsCurrentStudent) {
        return;
      }
      const interactionKey = buzzInteractionKey(questionId, eventRoundId);
      setLocallyBuzzedQuestionIds((current) => removeInteractionEntry(current, interactionKey));
      setAnswersByQuestionId((current) => removeInteractionEntry(current, interactionKey));
      if (latestEvent.type === "BUZZ_RESET") {
        setSubmittedQuestionIds((current) => removeQuestionRoundKeys(current, questionId));
      }
    }
  }, [realtime.events, currentRoundByQuestionId, session?.userId]);

  const refreshStudentView = useCallback(async () => {
    if (!session?.token || !session.classroomCode) {
      return;
    }

    const requestId = answerRefreshGateRef.current.begin();
    try {
      const result = await listQuestions(session.token, session.classroomCode);
      const loadedQuestions = result.questions as unknown as Array<Record<string, unknown>>;
      if (!answerRefreshGateRef.current.isCurrent(requestId)) {
        return;
      }
      setQuestions(loadedQuestions);
      const activeAttemptEntries = loadedQuestions
        .filter((question) => question.ownBuzzAttemptRecorded === true)
        .map((question) => [
          buzzInteractionKey(String(question.id), String(question.roundId ?? "0")),
          true
        ] as const);
      setLocallyBuzzedQuestionIds(Object.fromEntries(activeAttemptEntries));

      const answerResults = await Promise.allSettled(loadedQuestions.map((question) =>
        getCurrentAnswers(session.token, session.classroomCode!, String(question.id))
      ));
      if (!answerRefreshGateRef.current.isCurrent(requestId)) {
        return;
      }

      const loadedAnswersByQuestionId: Record<string, SubmittedAnswerView[]> = {};
      for (const answerResult of answerResults) {
        if (answerResult.status === "fulfilled") {
          loadedAnswersByQuestionId[answerResult.value.questionId] = answerResult.value.answers;
        }
      }
      setCurrentAnswersByQuestionId((current) => Object.fromEntries(
        loadedQuestions.map((question) => {
          const questionId = String(question.id);
          return [questionId, loadedAnswersByQuestionId[questionId] ?? current[questionId] ?? []];
        })
      ));
      const ownSubmittedKeys = ownSubmittedInteractionKeys(loadedQuestions, loadedAnswersByQuestionId);
      setSubmittedQuestionIds((current) => mergeSubmittedInteractionKeys(current, ownSubmittedKeys));
    } catch (error) {
      if (answerRefreshGateRef.current.isCurrent(requestId)) {
        setNotice(error instanceof Error ? error.message : "答案載入失敗。");
      }
    }
  }, [session?.token, session?.classroomCode]);

  useEffect(() => {
    void refreshStudentView();
    return () => answerRefreshGateRef.current.invalidate();
  }, [refreshStudentView, realtime.events]);

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
  async function handleSubmitAnswer(questionId: string, interactionKey: string, items: QuestionItemView[]) {
    if (submittedQuestionIds.includes(interactionKey) || submittingKeysRef.current.has(interactionKey)) return;
    submittingKeysRef.current.add(interactionKey);
    setSubmittingQuestionIds((current) => [...current, interactionKey]);
    try {
      const result = await submitPackageAnswers(
        activeSession.token,
        activeSession.classroomCode!,
        questionId,
        items.map((item) => ({
          itemId: item.id,
          answer: answersByQuestionId[interactionKey]?.[item.id] ?? ""
        }))
      );
      if (!result.accepted) {
        setNoticeByQuestionId((current) => ({
          ...current,
          [questionId]: submissionOutcomeMessage(result.outcomeStatus)
        }));
        return;
      }
      await refreshStudentView();
      setSubmittedQuestionIds((current) => mergeSubmittedInteractionKeys(current, [interactionKey]));
      setLocallyBuzzedQuestionIds((current) => {
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

  async function handleBuzz(questionId: string, interactionKey: string, items: QuestionItemView[]) {
    if (submittingKeysRef.current.has(interactionKey)) return;
    submittingKeysRef.current.add(interactionKey);
    setSubmittingQuestionIds((current) => [...current, interactionKey]);
    try {
      const buzzResult = await submitBuzz(activeSession.token, activeSession.classroomCode!, questionId);
      if (!buzzResult.accepted) {
        setNoticeByQuestionId((current) => ({
          ...current,
          [questionId]: buzzResult.outcomeStatus === "SUCCESS_LIMIT_REACHED"
            ? `第${buzzResult.groupNumber}組已達搶答成功上限（${buzzResult.groupSuccessfulBuzzCount}/${buzzResult.successfulBuzzLimit}），本次不記錄。`
            : "本次搶答未被記錄。"
        }));
        return;
      }

      setLocallyBuzzedQuestionIds((current) => ({ ...current, [interactionKey]: true }));
      const submissionResult = await submitPackageAnswers(
        activeSession.token,
        activeSession.classroomCode!,
        questionId,
        items.map((item) => ({
          itemId: item.id,
          answer: answersByQuestionId[interactionKey]?.[item.id] ?? ""
        }))
      );
      if (!submissionResult.accepted) {
        await cancelBuzz(activeSession.token, activeSession.classroomCode!, questionId).catch(() => undefined);
        setLocallyBuzzedQuestionIds((current) => removeInteractionEntry(current, interactionKey));
        setNoticeByQuestionId((current) => ({
          ...current,
          [questionId]: submissionOutcomeMessage(submissionResult.outcomeStatus)
        }));
        return;
      }

      await refreshStudentView();
      setSubmittedQuestionIds((current) => mergeSubmittedInteractionKeys(current, [interactionKey]));
      setLocallyBuzzedQuestionIds((current) => removeInteractionEntry(current, interactionKey));
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: "✓ 搶答成功，答案已送出。"
      }));
    } catch (error) {
      await cancelBuzz(activeSession.token, activeSession.classroomCode!, questionId).catch(() => undefined);
      setLocallyBuzzedQuestionIds((current) => removeInteractionEntry(current, interactionKey));
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: error instanceof Error ? error.message : "搶答失敗。"
      }));
    } finally {
      submittingKeysRef.current.delete(interactionKey);
      setSubmittingQuestionIds((current) => current.filter((key) => key !== interactionKey));
    }
  }

  async function handleCancelAttempt(questionId: string, interactionKey: string) {
    try {
      await cancelBuzz(activeSession.token, activeSession.classroomCode!, questionId);
      setLocallyBuzzedQuestionIds((current) => {
        const next = { ...current };
        delete next[interactionKey];
        return next;
      });
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: "已取消你本次的搶答，其他同學的搶答不受影響。"
      }));
    } catch (error) {
      setNoticeByQuestionId((current) => ({
        ...current,
        [questionId]: error instanceof Error ? error.message : "取消搶答失敗。"
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
              const winner = buzzWinnerByQuestionId[questionId];
              const winnerUserId = winner?.userId ?? "";
              const hasBuzzed = locallyBuzzedQuestionIds[interactionKey] === true;
              const currentAnswers = currentAnswersByQuestionId[questionId] ?? [];
              const hasSubmitted = submittedQuestionIds.includes(interactionKey)
                || currentAnswers.some((submission) => submission.isOwnSubmission);
              const isSubmitting = submittingQuestionIds.includes(interactionKey);
              const packageAnswers = answersByQuestionId[interactionKey] ?? {};
              const items = (question.items ?? []) as QuestionItemView[];
              const answersComplete = arePackageAnswersComplete(items, packageAnswers);
              const canBuzz = state === "BUZZ_OPEN" && answersComplete && !hasBuzzed && !hasSubmitted && !isSubmitting;
              const canAnswer = state === "ANSWER_OPEN";

              return (
                <article className="question-stage" key={questionId}>
                  <strong>{String(question.title ?? question.prompt)}</strong>
                  <ol className="question-item-list">
                    {items.map((item) => <li key={item.id}>{item.prompt}</li>)}
                  </ol>
                  <span className={`pill ${canBuzz ? "pill-open" : hasBuzzed ? "pill-won" : ""}`}>
                    {formatQuestionState(state)}
                  </span>
                  {winnerUserId ? <p className="muted">目前搶答成功者：{winnerUserId}</p> : null}
                  {noticeByQuestionId[questionId] ? <p className="notice">{noticeByQuestionId[questionId]}</p> : null}
                  {state === "BUZZ_OPEN" ? (
                    <>
                      {items.map((item) => (
                        <label key={item.id}>
                          {item.prompt}
                          <textarea
                            disabled={hasSubmitted || hasBuzzed || isSubmitting}
                            value={packageAnswers[item.id] ?? ""}
                            onChange={(event) => setAnswersByQuestionId((current) => ({
                              ...current,
                              [interactionKey]: {
                                ...(current[interactionKey] ?? {}),
                                [item.id]: event.target.value
                              }
                            }))}
                            placeholder="請先填寫答案"
                          />
                        </label>
                      ))}
                      {!answersComplete && !hasSubmitted ? <p className="muted">完成所有答案後即可搶答。</p> : null}
                      <button
                        className={`buzz-button ${canBuzz ? "buzz-button-open" : hasBuzzed ? "buzz-button-won" : "buzz-button-locked"}`}
                        disabled={!canBuzz}
                        onClick={() => void handleBuzz(questionId, interactionKey, items)}
                      >
                        {isSubmitting ? "搶答送出中…" : canBuzz ? "搶答並送出答案" : hasBuzzed ? "搶答處理中" : hasSubmitted ? "已提交" : "請先完成答案"}
                      </button>
                      {hasBuzzed ? (
                        canCancelBuzz(hasBuzzed, hasSubmitted) && !isSubmitting ? (
                          <button type="button" className="secondary-button" onClick={() => void handleCancelAttempt(questionId, interactionKey)}>
                            取消本次搶答
                          </button>
                        ) : null
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
