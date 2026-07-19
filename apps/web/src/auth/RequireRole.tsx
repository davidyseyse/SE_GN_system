import type { ReactElement } from "react";
import type { Role } from "@classroom/shared";
import { Navigate } from "react-router-dom";
import { hasSession } from "../lib/session";

type RequireRoleProps = {
  role: Role;
  children: ReactElement;
};

export function RequireRole({ role, children }: RequireRoleProps) {
  if (!hasSession(role)) {
    return <Navigate to="/login" replace />;
  }

  return children;
}
