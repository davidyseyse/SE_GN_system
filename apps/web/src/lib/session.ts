import type { Role } from "@classroom/shared";

type StoredSession = {
  token: string;
  userId: string;
  displayName: string;
  role: Role;
  classroomCode?: string;
  classroomId?: string;
};

const STORAGE_KEY = "classroom-web-session";

export function readSession(): StoredSession | null {
  const raw = localStorage.getItem(STORAGE_KEY);
  if (!raw) {
    return null;
  }
  try {
    return JSON.parse(raw) as StoredSession;
  } catch {
    return null;
  }
}

export function writeSession(session: StoredSession) {
  localStorage.setItem(STORAGE_KEY, JSON.stringify(session));
}

export function clearSession() {
  localStorage.removeItem(STORAGE_KEY);
}
