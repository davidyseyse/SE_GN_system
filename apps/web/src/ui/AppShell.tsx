import { NavLink, Outlet } from "react-router-dom";

export function AppShell() {
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
          </nav>
        </div>
      </header>
      <main className="page-wrap">
        <Outlet />
      </main>
    </div>
  );
}
