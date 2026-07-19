import { createBrowserRouter, Navigate } from "react-router-dom";
import { LoginPage } from "./auth/LoginPage";
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
      { index: true, element: <Navigate to="/login" replace /> },
      { path: "login", element: <LoginPage /> },
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
