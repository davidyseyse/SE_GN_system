import { useEffect, useState } from "react";
import { useNavigate } from "react-router-dom";
import { type ClassroomView, joinClassroom, listAvailableClassrooms, studentLogin } from "../lib/api";
import { writeSession } from "../lib/session";

export function StudentLoginPage() {
  const navigate = useNavigate();
  const [studentId, setStudentId] = useState("");
  const [groupNumber, setGroupNumber] = useState("");
  const [classroomCode, setClassroomCode] = useState("");
  const [classrooms, setClassrooms] = useState<ClassroomView[]>([]);
  const [notice, setNotice] = useState("請輸入學號、組別並選擇課堂。");

  useEffect(() => {
    void listAvailableClassrooms()
      .then((result) => setClassrooms(result.classrooms))
      .catch((error) => setNotice(error instanceof Error ? error.message : "無法載入課堂清單。"));
  }, []);

  const parsedGroupNumber = Number.parseInt(groupNumber, 10);
  const canJoin = Boolean(
    studentId.trim()
    && Number.isInteger(parsedGroupNumber)
    && parsedGroupNumber > 0
    && classroomCode
  );

  async function handleLogin() {
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
      setNotice("請選擇課堂。");
      return;
    }

    try {
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
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "學生登入失敗。");
    }
  }

  return (
    <section className="center-panel">
      <div className="panel student-panel">
        <h2>學生登入</h2>
        <p className="muted">{notice}</p>
        <div className="stack-form">
          <input value={studentId} onChange={(event) => setStudentId(event.target.value.replace(/\s+/g, ""))} placeholder="學號" />
          <input value={groupNumber} onChange={(event) => setGroupNumber(event.target.value.replace(/[^\d]/g, ""))} placeholder="組別（正整數）" />
          <select aria-label="選擇課堂" value={classroomCode} onChange={(event) => setClassroomCode(event.target.value)}>
            <option value="">請選擇課堂</option>
            {classrooms.map((classroom) => (
              <option key={classroom.id} value={classroom.code}>
                {classroom.name}（{classroom.code}）
              </option>
            ))}
          </select>
          <button disabled={!canJoin} onClick={() => void handleLogin()}>加入課堂</button>
        </div>
      </div>
    </section>
  );
}
