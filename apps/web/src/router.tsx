import { createBrowserRouter, Navigate } from "react-router-dom";
import { StudentLoginPage } from "./auth/StudentLoginPage";
import { TeacherLoginPage } from "./auth/TeacherLoginPage";
import { RequireRole } from "./auth/RequireRole";
import { StudentJoinPage } from "./student/StudentJoinPage";
import { StudentPlayPage } from "./student/StudentPlayPage";
import { TeacherDashboardPage } from "./teacher/TeacherDashboardPage";
import { AppShell } from "./ui/AppShell";

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/student/login" replace /> },
      { path: "login", element: <Navigate to="/student/login" replace /> },
      { path: "teacher/login", element: <TeacherLoginPage /> },
      { path: "student/login", element: <StudentLoginPage /> },
      {
        path: "teacher",
        element: (
          <RequireRole role="TEACHER">
            <TeacherDashboardPage />
          </RequireRole>
        )
      },
      {
        path: "student",
        element: (
          <RequireRole role="STUDENT">
            <StudentJoinPage />
          </RequireRole>
        )
      },
      {
        path: "student/live",
        element: (
          <RequireRole role="STUDENT">
            <StudentPlayPage />
          </RequireRole>
        )
      }
    ]
  }
]);
