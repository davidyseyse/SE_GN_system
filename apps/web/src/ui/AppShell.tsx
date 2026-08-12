import { NavLink, Outlet, useLocation } from "react-router-dom";

export function AppShell() {
  const location = useLocation();
  const isTeacherArea = location.pathname.startsWith("/teacher");

  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">即時課堂測驗</p>
          <h1>搶答系統控制台</h1>
        </div>
        <div className="topbar-actions">
          <nav className="topnav">
            {isTeacherArea ? (
              <NavLink to="/teacher/login" className={({ isActive }) => isActive ? "active" : ""}>教師登入</NavLink>
            ) : (
              <NavLink to="/student/login" className={({ isActive }) => isActive ? "active" : ""}>學生登入</NavLink>
            )}
          </nav>
        </div>
      </header>
      <main className="page-wrap">
        <Outlet />
      </main>
    </div>
  );
}
