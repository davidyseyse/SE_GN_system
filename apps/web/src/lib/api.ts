import type {
  ClassroomParticipantContract,
  ClassroomQuestionContract,
  LeaderboardVisibility,
  Role
} from "@classroom/shared";

const API_BASE_URL = import.meta.env.VITE_API_BASE_URL ?? "http://localhost:8080";

type TokenResponse = {
  accessToken: string;
  tokenType: string;
  expiresInSeconds: number;
  user: {
    id: string;
    displayName: string;
    role: Role;
  };
};

type RealtimeConfig = {
  transport: string;
  url: string;
  path: string;
};

export type StudentLoginResponse = TokenResponse & {
  studentId: string;
  groupNumber: number;
};

export type QuestionView = ClassroomQuestionContract & {
  answerKey: string;
  publicationSettings: string;
  answerTimeLimitSeconds: string;
  archivedAt: string;
};

export type SheetConfigView = {
  id: string;
  name: string;
  sheetId: string;
  createdAt: string;
};

async function request<T>(path: string, init?: RequestInit, token?: string): Promise<T> {
  const headers = new Headers(init?.headers);
  headers.set("Content-Type", "application/json");
  if (token) {
    headers.set("Authorization", `Bearer ${token}`);
  }

  const response = await fetch(`${API_BASE_URL}${path}`, {
    ...init,
    headers
  });

  const data = await response.json();
  if (!response.ok) {
    throw new Error(data.error ?? data.message ?? "請求失敗");
  }
  return data as T;
}

export async function adminLogin(username: string, password: string): Promise<TokenResponse> {
  return request<TokenResponse>("/api/auth/admin-login", {
    method: "POST",
    body: JSON.stringify({ username, password })
  });
}

export async function studentLogin(studentId: string, groupNumber: number): Promise<StudentLoginResponse> {
  return request<StudentLoginResponse>("/api/auth/student-login", {
    method: "POST",
    body: JSON.stringify({ studentId, groupNumber })
  });
}

export async function createClassroom(
  token: string,
  name: string,
  leaderboardVisibility: LeaderboardVisibility,
  settings: string
) {
  return request<{
    classroom: {
      id: string;
      code: string;
      name: string;
      status: string;
      leaderboardVisibility: string;
    };
  }>("/api/classrooms", {
    method: "POST",
    body: JSON.stringify({
      name,
      leaderboardVisibility,
      participationSettings: settings
    })
  }, token);
}

export async function listSheetConfigs(token: string) {
  return request<{ configs: SheetConfigView[] }>("/api/sheet-configs", undefined, token);
}

export async function createSheetConfig(token: string, name: string, sheetId: string) {
  return request<{ config: SheetConfigView }>("/api/sheet-configs", {
    method: "POST",
    body: JSON.stringify({ name, sheetId })
  }, token);
}

export async function deleteSheetConfig(token: string, configId: string) {
  return request<{ deleted: string }>("/api/sheet-configs/delete", {
    method: "POST",
    body: JSON.stringify({ configId })
  }, token);
}

export async function joinClassroom(token: string, classroomCode: string) {
  return request<{
    classroom: {
      id: string;
      code: string;
      name: string;
      status: string;
    };
    participant: ClassroomParticipantContract;
  }>("/api/classrooms/join", {
    method: "POST",
    body: JSON.stringify({ classroomCode })
  }, token);
}

export async function getMembers(token: string, classroomCode: string) {
  return request<{ members: Array<ClassroomParticipantContract & { presenceStatus: string; joinedAt: string; lastSeenAt: string }> }>(
    `/api/classrooms/members?classroomCode=${encodeURIComponent(classroomCode)}`,
    undefined,
    token
  );
}

export async function createQuestion(
  token: string,
  classroomCode: string,
  payload: { prompt: string; type: string; score: number; publicationSettings: string; answerTimeLimitSeconds?: number }
) {
  return request<{ question: QuestionView }>("/api/questions", {
    method: "POST",
    body: JSON.stringify({
      classroomCode,
      ...payload
    })
  }, token);
}

export async function updateQuestionState(token: string, classroomCode: string, questionId: string, targetState: string) {
  return request<{ question: QuestionView }>("/api/questions/state", {
    method: "POST",
    body: JSON.stringify({ classroomCode, questionId, targetState })
  }, token);
}

export async function listQuestions(token: string, classroomCode: string) {
  return request<{ questions: QuestionView[] }>(
    `/api/questions?classroomCode=${encodeURIComponent(classroomCode)}`,
    undefined,
    token
  );
}

export async function getLeaderboard(token: string, classroomCode: string) {
  return request<{ leaderboard: Array<{ rank: number; userId: string; displayName: string; score: number }> }>(
    `/api/leaderboard?classroomCode=${encodeURIComponent(classroomCode)}`,
    undefined,
    token
  );
}

export async function getSummary(token: string, classroomCode: string) {
  return request<{
    participantCount: number;
    questionCount: number;
    submissionCount: number;
    buzzCount: number;
    scoreRecordCount: number;
  }>(`/api/reports/summary?classroomCode=${encodeURIComponent(classroomCode)}`, undefined, token);
}

export async function getRealtimeConfig(token: string) {
  return request<RealtimeConfig>("/api/realtime/config", undefined, token);
}

export async function openRealtimeSession(token: string, classroomCode: string) {
  return request<{ sessionId: string; classroomCode: string }>("/api/realtime/session", {
    method: "POST",
    body: JSON.stringify({ classroomCode })
  }, token);
}

export async function submitAnswer(token: string, classroomCode: string, questionId: string, answer: string) {
  return request<{ submissionId: string; isCorrect: string; reviewStatus: string }>(
    "/api/submissions",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, questionId, answer })
    },
    token
  );
}

export async function submitBuzz(token: string, classroomCode: string, questionId: string) {
  return request<{ accepted: boolean; outcomeStatus: string; requestId: string }>(
    "/api/buzz",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, questionId, requestId: crypto.randomUUID() })
    },
    token
  );
}

export async function cancelBuzz(token: string, classroomCode: string, questionId: string) {
  return request<{ status: string }>(
    "/api/buzz/cancel",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, questionId })
    },
    token
  );
}

export async function resetBuzz(token: string, classroomCode: string, questionId?: string) {
  return request<{ status: string }>(
    "/api/buzz/reset",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, questionId: questionId ?? "" })
    },
    token
  );
}
