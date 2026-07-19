import { useState } from "react";
import type { Role } from "@classroom/shared";
import { useNavigate } from "react-router-dom";
import { adminLogin, joinClassroom, studentLogin } from "../lib/api";
import { writeSession } from "../lib/session";

export function LoginPage() {
  const navigate = useNavigate();
  const [role, setRole] = useState<Role>("TEACHER");
  const [username, setUsername] = useState("teacher");
  const [password, setPassword] = useState("teacher123");
  const [studentId, setStudentId] = useState("");
  const [groupNumber, setGroupNumber] = useState("");
  const [classroomCode, setClassroomCode] = useState("");
  const [notice, setNotice] = useState("請選擇身分登入。教師需輸入帳號密碼；學生需輸入學號、組別與課堂代碼。");

  async function handleTeacherLogin() {
    const response = await adminLogin(username.trim(), password);
    writeSession({
      token: response.accessToken,
      userId: response.user.id,
      displayName: response.user.displayName,
      role: response.user.role
    });
    void navigate("/teacher");
  }

  async function handleStudentLogin() {
    const normalizedStudentId = studentId.trim();
    const group = Number.parseInt(groupNumber, 10);
    if (!normalizedStudentId) {
      setNotice("請輸入學號。");
      return;
    }
    if (!Number.isInteger(group) || group <= 0) {
      setNotice("組別必須是正整數。");
      return;
    }
    if (!classroomCode.trim()) {
      setNotice("請輸入課堂代碼。");
      return;
    }

    const response = await studentLogin(normalizedStudentId, group);
    const joined = await joinClassroom(response.accessToken, classroomCode.trim().toUpperCase());
    writeSession({
      token: response.accessToken,
      userId: response.user.id,
      displayName: response.user.displayName,
      role: response.user.role,
      classroomCode: joined.classroom.code,
      classroomId: joined.classroom.id,
      studentId: response.studentId,
      groupNumber: response.groupNumber
    });
    void navigate("/student/live");
  }

  return (
    <section className="center-panel">
      <div className="panel student-panel">
        <h2>登入</h2>
        <p className="muted">{notice}</p>
        <div className="stack-form">
          <select value={role} onChange={(event) => setRole(event.target.value as Role)}>
            <option value="TEACHER">管理者（教師）</option>
            <option value="STUDENT">學生</option>
          </select>

          {role === "TEACHER" ? (
            <>
              <input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="管理者帳號" />
              <input value={password} onChange={(event) => setPassword(event.target.value)} type="password" placeholder="管理者密碼" />
              <button onClick={() => void handleTeacherLogin()}>登入管理主控台</button>
            </>
          ) : (
            <>
              <input value={studentId} onChange={(event) => setStudentId(event.target.value.replace(/\s+/g, ""))} placeholder="學號" />
              <input value={groupNumber} onChange={(event) => setGroupNumber(event.target.value.replace(/[^\d]/g, ""))} placeholder="組別（正整數）" />
              <input value={classroomCode} onChange={(event) => setClassroomCode(event.target.value.toUpperCase())} placeholder="課堂代碼" />
              <button onClick={() => void handleStudentLogin()}>進入搶答 / 加入大廳</button>
            </>
          )}
        </div>
      </div>
    </section>
  );
}
