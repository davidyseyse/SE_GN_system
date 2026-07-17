import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { issueToken, joinClassroom } from "../lib/api";
import { readSession, writeSession } from "../lib/session";

export function StudentJoinPage() {
  const navigate = useNavigate();
  const stored = readSession();
  const [displayName, setDisplayName] = useState(stored?.displayName ?? "Student");
  const [classroomCode, setClassroomCode] = useState(stored?.classroomCode ?? "");
  const [notice, setNotice] = useState("Join an active classroom with the classroom code.");

  async function handleJoin() {
    const tokenResponse = await issueToken(displayName, "STUDENT", `student-${displayName.toLowerCase().replace(/\s+/g, "-")}`);
    const joined = await joinClassroom(tokenResponse.accessToken, classroomCode);
    writeSession({
      token: tokenResponse.accessToken,
      userId: tokenResponse.user.id,
      displayName: tokenResponse.user.displayName,
      role: tokenResponse.user.role,
      classroomCode: joined.classroom.code,
      classroomId: joined.classroom.id
    });
    setNotice(`Joined ${joined.classroom.name}. Redirecting to live board.`);
    void navigate("/student/live");
  }

  return (
    <section className="center-panel">
      <div className="panel student-panel">
        <h2>Student Entry</h2>
        <p className="muted">{notice}</p>
        <div className="stack-form">
          <input value={displayName} onChange={(event) => setDisplayName(event.target.value)} placeholder="Student name" />
          <input value={classroomCode} onChange={(event) => setClassroomCode(event.target.value.toUpperCase())} placeholder="Classroom code" />
          <button onClick={() => void handleJoin()}>Join Classroom</button>
        </div>
      </div>
    </section>
  );
}
