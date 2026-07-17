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

export type TeacherSession = {
  token: string;
  userId: string;
  displayName: string;
};

export type StudentSession = TeacherSession;

export type ClassroomSummary = {
  classroom: {
    id: string;
    code: string;
    name: string;
    status: string;
    leaderboardVisibility: string;
  };
};

export type QuestionView = ClassroomQuestionContract & {
  answerKey: string;
  publicationSettings: string;
  answerTimeLimitSeconds: string;
  archivedAt: string;
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
    throw new Error(data.error ?? data.message ?? "Request failed");
  }
  return data as T;
}

export async function issueToken(displayName: string, role: Role, userId: string): Promise<TokenResponse> {
  return request<TokenResponse>("/api/auth/token", {
    method: "POST",
    body: JSON.stringify({ userId, displayName, role })
  });
}

export async function createClassroom(
  token: string,
  name: string,
  leaderboardVisibility: LeaderboardVisibility,
  settings: string
): Promise<ClassroomSummary> {
  return request<ClassroomSummary>("/api/classrooms", {
    method: "POST",
    body: JSON.stringify({
      name,
      leaderboardVisibility,
      participationSettings: settings
    })
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

export async function getPresence(token: string, classroomCode: string) {
  return request<{
    onlineCount: number;
    participants: Array<{ userId: string; role: Role; presenceStatus: string; lastSeenAt: string }>;
  }>(`/api/classrooms/presence?classroomCode=${encodeURIComponent(classroomCode)}`, undefined, token);
}

export async function createQuestion(
  token: string,
  classroomCode: string,
  payload: { prompt: string; type: string; score: number; answerKey: string; publicationSettings: string; answerTimeLimitSeconds?: number }
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
  return request<{ accepted: boolean; outcomeStatus: string }>(
    "/api/buzz",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, questionId, requestId: crypto.randomUUID() })
    },
    token
  );
}
