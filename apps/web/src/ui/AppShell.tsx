import { NavLink, Outlet, useNavigate } from "react-router-dom";
import { clearSession, readSession } from "../lib/session";

export function AppShell() {
  const navigate = useNavigate();
  const session = readSession();

  function handleLogout() {
    clearSession();
    void navigate("/login");
  }

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">即時課堂測驗</p>
          <h1>搶答系統控制台</h1>
        </div>
        <div className="topbar-actions">
          <nav className="topnav">
            <NavLink to="/login" className={({ isActive }) => isActive ? "active" : ""}>登入</NavLink>
            <NavLink to="/teacher" className={({ isActive }) => isActive ? "active" : ""}>教師端</NavLink>
            <NavLink to="/student" className={({ isActive }) => isActive ? "active" : ""}>學生端</NavLink>
          </nav>
          {session ? (
            <div className="session-bar">
              <span>{session.displayName} / {session.role === "TEACHER" ? "教師" : "學生"}</span>
              <button type="button" className="secondary-button" onClick={handleLogout}>登出</button>
            </div>
          ) : null}
        </div>
      </header>
      <main className="page-wrap">
        <Outlet />
      </main>
    </div>
  );
}
