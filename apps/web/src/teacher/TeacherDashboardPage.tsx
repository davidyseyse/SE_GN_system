import { useEffect, useMemo, useState } from "react";
import type { LeaderboardVisibility } from "@classroom/shared";
import {
  type QuestionView,
  createClassroom,
  createQuestion,
  getLeaderboard,
  getMembers,
  getSummary,
  issueToken,
  listQuestions,
  updateQuestionState
} from "../lib/api";
import { readSession, writeSession } from "../lib/session";
import { useRealtimeFeed } from "../realtime/useRealtimeFeed";

type RealtimeEventView = {
  type: string;
  occurredAt: string;
};

const defaultSettings = "{\"buzzBonus\":5,\"wrongAnswerPenalty\":2,\"showAnswer\":false}";

export function TeacherDashboardPage() {
  const stored = readSession();
  const [displayName, setDisplayName] = useState(stored?.displayName ?? "Teacher");
  const [teacherToken, setTeacherToken] = useState<string | null>(stored?.role === "TEACHER" ? stored.token : null);
  const [classroomCode, setClassroomCode] = useState(stored?.classroomCode ?? "");
  const [classroomName, setClassroomName] = useState("Classroom A");
  const [leaderboardVisibility, setLeaderboardVisibility] = useState<LeaderboardVisibility>("TOP_10");
  const [questionPrompt, setQuestionPrompt] = useState("What is the capital of France?");
  const [questionAnswer, setQuestionAnswer] = useState("Paris");
  const [questionType, setQuestionType] = useState("SHORT_ANSWER");
  const [questions, setQuestions] = useState<QuestionView[]>([]);
  const [leaderboard, setLeaderboard] = useState<Array<Record<string, unknown>>>([]);
  const [members, setMembers] = useState<Array<Record<string, unknown>>>([]);
  const [summary, setSummary] = useState<Record<string, unknown> | null>(null);
  const [notice, setNotice] = useState("Create a classroom and start the live session.");
  const realtime = useRealtimeFeed(teacherToken, classroomCode || null);

  const lastRealtimeEvent = useMemo(
    () => realtime.events.length > 0 ? realtime.events[realtime.events.length - 1] : null,
    [realtime.events]
  );

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

  async function handleLogin() {
    const response = await issueToken(displayName, "TEACHER", `teacher-${displayName.toLowerCase().replace(/\s+/g, "-")}`);
    setTeacherToken(response.accessToken);
    writeSession({
      token: response.accessToken,
      userId: response.user.id,
      displayName: response.user.displayName,
      role: response.user.role,
      classroomCode
    });
    setNotice("Teacher token issued.");
  }

  async function handleCreateClassroom() {
    if (!teacherToken) {
      return;
    }
    const result = await createClassroom(teacherToken, classroomName, leaderboardVisibility, defaultSettings);
    setClassroomCode(result.classroom.code);
    writeSession({
      token: teacherToken,
      userId: "teacher",
      displayName,
      role: "TEACHER",
      classroomCode: result.classroom.code,
      classroomId: result.classroom.id
    });
    setNotice(`Classroom ${result.classroom.code} created.`);
  }

  async function handleCreateQuestion() {
    if (!teacherToken || !classroomCode) {
      return;
    }
    const result = await createQuestion(teacherToken, classroomCode, {
      prompt: questionPrompt,
      type: questionType,
      score: 10,
      answerKey: questionAnswer,
      publicationSettings: "{\"showAnswer\":false}",
      answerTimeLimitSeconds: 30
    });
    setQuestions((current) => [...current, result.question]);
    setNotice("Question created.");
  }

  async function handleStateChange(questionId: string, targetState: string) {
    if (!teacherToken || !classroomCode) {
      return;
    }
    await updateQuestionState(teacherToken, classroomCode, questionId, targetState);
    const result = await listQuestions(teacherToken, classroomCode);
    setQuestions(result.questions);
    setNotice(`Question moved to ${targetState}.`);
  }

  return (
    <section className="grid-layout">
      <div className="panel hero-panel">
        <h2>Teacher Console</h2>
        <p className="muted">
          Issue a teacher token, create a classroom, then control question state and watch realtime updates.
        </p>
        <div className="inline-form">
          <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="Teacher name" />
          <button onClick={() => void handleLogin()}>Issue Token</button>
        </div>
        <div className="inline-form">
          <input value={classroomName} onChange={(event) => setClassroomName(event.target.value)} placeholder="Classroom name" />
          <select value={leaderboardVisibility} onChange={(event) => setLeaderboardVisibility(event.target.value as LeaderboardVisibility)}>
            <option value="HIDDEN">Hidden</option>
            <option value="TOP_3">Top 3</option>
            <option value="TOP_10">Top 10</option>
            <option value="FULL">Full</option>
          </select>
          <button disabled={!teacherToken} onClick={() => void handleCreateClassroom()}>Create Classroom</button>
        </div>
        <div className="status-strip">
          <span>Classroom: {classroomCode || "Not created"}</span>
          <span>Realtime: {realtime.status}</span>
        </div>
        <p className="notice">{notice}</p>
      </div>

      <div className="panel">
        <h3>Question Bank</h3>
        <div className="stack-form">
          <input value={questionPrompt} onChange={(event) => setQuestionPrompt(event.target.value)} placeholder="Question prompt" />
          <div className="inline-form">
            <select value={questionType} onChange={(event) => setQuestionType(event.target.value)}>
              <option value="SHORT_ANSWER">Short Answer</option>
              <option value="SINGLE_CHOICE">Single Choice</option>
              <option value="MULTIPLE_CHOICE">Multiple Choice</option>
              <option value="TRUE_FALSE">True/False</option>
              <option value="BUZZ">Buzz</option>
            </select>
            <input value={questionAnswer} onChange={(event) => setQuestionAnswer(event.target.value)} placeholder="Answer key" />
            <button disabled={!teacherToken || !classroomCode} onClick={() => void handleCreateQuestion()}>Add Question</button>
          </div>
        </div>
        <div className="card-list">
          {questions.map((question) => (
            <article key={String(question.id)} className="mini-card">
              <div className="mini-card__head">
                <strong>{String(question.prompt)}</strong>
                <span>{String(question.state)}</span>
              </div>
              <div className="action-row">
                <button onClick={() => void handleStateChange(String(question.id), "READY")}>Ready</button>
                <button onClick={() => void handleStateChange(String(question.id), "BUZZ_OPEN")}>Buzz</button>
                <button onClick={() => void handleStateChange(String(question.id), "ANSWER_OPEN")}>Answer</button>
                <button onClick={() => void handleStateChange(String(question.id), "PUBLISHED")}>Publish</button>
              </div>
            </article>
          ))}
        </div>
      </div>

      <div className="panel">
        <h3>Live Metrics</h3>
        <div className="stat-grid">
          <div><span>Participants</span><strong>{String(summary?.participantCount ?? 0)}</strong></div>
          <div><span>Questions</span><strong>{String(summary?.questionCount ?? 0)}</strong></div>
          <div><span>Submissions</span><strong>{String(summary?.submissionCount ?? 0)}</strong></div>
          <div><span>Buzzes</span><strong>{String(summary?.buzzCount ?? 0)}</strong></div>
        </div>
        <h4>Leaderboard</h4>
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
        <h3>Presence & Feed</h3>
        <ul className="presence-list">
          {members.map((member) => (
            <li key={String(member.id)}>
              <span>{String(member.userId)}</span>
              <span>{String(member.presenceStatus)}</span>
            </li>
          ))}
        </ul>
        <div className="feed">
          {(realtime.events as RealtimeEventView[]).slice(-8).reverse().map((event, index) => (
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
