import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { joinClassroom } from "../lib/api";
import { readSession, writeSession } from "../lib/session";

export function StudentJoinPage() {
  const navigate = useNavigate();
  const stored = readSession();
  const [classroomCode, setClassroomCode] = useState(stored?.classroomCode ?? "");
  const [notice, setNotice] = useState("請輸入課堂代碼加入搶答大廳。");

  async function handleJoin() {
    if (!stored?.token) {
      setNotice("請先登入學生帳號。");
      return;
    }

    const joined = await joinClassroom(stored.token, classroomCode);
    writeSession({
      ...stored,
      classroomCode: joined.classroom.code,
      classroomId: joined.classroom.id
    });
    setNotice(`已加入 ${joined.classroom.name}，正在進入搶答大廳。`);
    void navigate("/student/live");
  }

  return (
    <section className="center-panel">
      <div className="panel student-panel">
        <h2>加入搶答大廳</h2>
        <p className="muted">{notice}</p>
        <div className="stack-form">
          <input value={stored?.studentId ?? ""} disabled />
          <input value={stored?.groupNumber?.toString() ?? ""} disabled />
          <input value={classroomCode} onChange={(event) => setClassroomCode(event.target.value.toUpperCase())} placeholder="課堂代碼" />
          <button onClick={() => void handleJoin()}>加入大廳</button>
        </div>
      </div>
    </section>
  );
}
