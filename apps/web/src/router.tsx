import { createBrowserRouter, Navigate } from "react-router-dom";
import { AppShell } from "./ui/AppShell";
import { TeacherDashboardPage } from "./teacher/TeacherDashboardPage";
import { StudentJoinPage } from "./student/StudentJoinPage";
import { StudentPlayPage } from "./student/StudentPlayPage";

export const router = createBrowserRouter([
  {
    path: "/",
    element: <AppShell />,
    children: [
      { index: true, element: <Navigate to="/teacher" replace /> },
      { path: "teacher", element: <TeacherDashboardPage /> },
      { path: "student", element: <StudentJoinPage /> },
      { path: "student/live", element: <StudentPlayPage /> }
    ]
  }
]);
