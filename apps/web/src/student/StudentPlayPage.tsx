import { useEffect, useMemo, useState } from "react";
import { getLeaderboard, listQuestions, submitAnswer, submitBuzz } from "../lib/api";
import { clearSession, readSession } from "../lib/session";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";

type RealtimeEventView = {
  type: string;
  occurredAt: string;
};

export function StudentPlayPage() {
  const session = readSession();
  const [questions, setQuestions] = useState<Array<Record<string, unknown>>>([]);
  const [answer, setAnswer] = useState("");
  const [leaderboard, setLeaderboard] = useState<Array<Record<string, unknown>>>([]);
  const [notice, setNotice] = useState("Waiting for the teacher to open a question.");
  const realtime = useRealtimeFeed(session?.token ?? null, session?.classroomCode ?? null);

  const activeQuestion = useMemo(() => {
    const visible = questions.find((question) => {
      const state = String(question.state);
      return state === "ANSWER_OPEN" || state === "BUZZ_OPEN" || state === "PUBLISHED";
    });
    return visible ?? null;
  }, [questions]);

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
          <h2>No Student Session</h2>
          <button onClick={() => clearSession()}>Reset Session</button>
        </div>
      </section>
    );
  }

  const activeSession = session;

  async function handleSubmitAnswer() {
    if (!activeQuestion) {
      return;
    }
    const result = await submitAnswer(activeSession.token, activeSession.classroomCode!, String(activeQuestion.id), answer);
    setNotice(`Submission recorded. Review status: ${result.reviewStatus || "graded"}`);
    setAnswer("");
  }

  async function handleBuzz() {
    if (!activeQuestion) {
      return;
    }
    const result = await submitBuzz(activeSession.token, activeSession.classroomCode!, String(activeQuestion.id));
    setNotice(result.accepted ? "You won the buzz." : `Buzz rejected: ${result.outcomeStatus}`);
  }

  return (
    <section className="grid-layout student-live">
      <div className="panel hero-panel">
        <h2>{activeSession.classroomCode}</h2>
        <p className="muted">Realtime status: {realtime.status}</p>
        <p className="notice">{notice}</p>
      </div>

      <div className="panel">
        <h3>Current Question</h3>
        {activeQuestion ? (
          <div className="question-stage">
            <strong>{String(activeQuestion.prompt)}</strong>
            <span className="pill">{String(activeQuestion.state)}</span>
            {String(activeQuestion.state) === "BUZZ_OPEN" ? (
              <button className="buzz-button" onClick={() => void handleBuzz()}>Buzz In</button>
            ) : (
              <>
                <textarea value={answer} onChange={(event) => setAnswer(event.target.value)} placeholder="Type your answer" />
                <button onClick={() => void handleSubmitAnswer()}>Submit Answer</button>
              </>
            )}
          </div>
        ) : (
          <p className="muted">No active question yet.</p>
        )}
      </div>

      <div className="panel">
        <h3>Personal Leaderboard View</h3>
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
        <h3>Realtime Feed</h3>
        <div className="feed">
          {(realtime.events as RealtimeEventView[]).slice(-10).reverse().map((event, index) => (
            <div key={`${event.occurredAt}-${index}`} className="feed-item">
              <strong>{event.type}</strong>
              <span>{event.occurredAt}</span>
            </div>
          ))}
        </div>
      </div>
    </section>
  );
}
