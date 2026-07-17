import { NavLink, Outlet } from "react-router-dom";

export function AppShell() {
  return (
    <div className="app-shell">
      <header className="topbar">
        <div>
          <p className="eyebrow">Live Classroom Quiz</p>
          <h1>Realtime Classroom Control</h1>
        </div>
        <nav className="topnav">
          <NavLink to="/teacher" className={({ isActive }) => isActive ? "active" : ""}>
            Teacher
          </NavLink>
          <NavLink to="/student" className={({ isActive }) => isActive ? "active" : ""}>
            Student
          </NavLink>
        </nav>
      </header>
      <main className="page-wrap">
        <Outlet />
      </main>
    </div>
  );
}
