import { useState } from "react";
import { useNavigate } from "react-router-dom";
import { adminLogin } from "../lib/api";
import { writeSession } from "../lib/session";

export function TeacherLoginPage() {
  const navigate = useNavigate();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [notice, setNotice] = useState("請輸入教師管理帳號與密碼。");

  async function handleLogin() {
    try {
      const response = await adminLogin(username.trim(), password);
      writeSession({
        token: response.accessToken,
        userId: response.user.id,
        displayName: response.user.displayName,
        role: response.user.role
      });
      void navigate("/teacher");
    } catch (error) {
      setNotice(error instanceof Error ? error.message : "教師登入失敗。");
    }
  }

  return (
    <section className="center-panel">
      <div className="panel student-panel">
        <h2>教師登入</h2>
        <p className="muted">{notice}</p>
        <div className="stack-form">
          <input value={username} onChange={(event) => setUsername(event.target.value)} placeholder="管理者帳號" />
          <input
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            type="password"
            autoComplete="current-password"
            placeholder="管理者密碼"
          />
          <button onClick={() => void handleLogin()}>登入管理主控台</button>
        </div>
      </div>
    </section>
  );
}
