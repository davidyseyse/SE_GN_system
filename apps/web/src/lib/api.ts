import type {
  ClassroomParticipantContract,
  ClassroomQuestionContract,
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
  packageId?: string;
  title?: string;
  items?: QuestionItemView[];
  answerKey: string;
  publicationSettings: string;
  answerTimeLimitSeconds: string;
  archivedAt: string;
  roundId: string;
  buzzStatus?: "NOT_OPEN" | "WAITING_FOR_BUZZ" | "WINNER_ANSWERING" | "SUBMITTED_PENDING_REVIEW" | "STOPPED";
  winnerUserId?: string;
  winnerExternalUserId?: string;
  winnerDisplayName?: string;
  ownBuzzAttemptRecorded?: boolean;
  submissionStatus?: string;
  reviewStatus?: string;
};

export type QuestionItemView = {
  id: string;
  ordinal: number;
  prompt: string;
  targetCell?: string;
  sourceSheetConfigId?: string;
  sourceWorksheet?: string;
  sourceCell?: string;
  sourceMarker?: string;
  answerSheetConfigId?: string;
  answerWorksheet?: string;
};

export type GeneratedQuestionItemPreview = {
  code: string;
  prompt: string;
  sourceCell: string;
  targetCell: string;
  sourceMarker: string;
  conflicts: string[];
};

export type GeneratedQuestionPackagePreview = {
  code: string;
  title: string;
  selected: boolean;
  items: GeneratedQuestionItemPreview[];
};

export type GeneratedQuestionScan = {
  scanToken: string;
  packages: GeneratedQuestionPackagePreview[];
  warnings: string[];
  errors: string[];
};

export type CreateQuestionPackagePayload = {
  title: string;
  sheetConfigId: string;
  items: Array<{ prompt: string; targetCell: string }>;
  score?: number;
  answerTimeLimitSeconds?: number;
};

export type QuestionBatchResult = {
  questionId: string;
  previousState: string;
  resultingState: string;
  result: "CHANGED" | "UNCHANGED" | "SKIPPED" | "FAILED";
  roundId: string;
  reason: string;
};

export type SheetConfigView = {
  id: string;
  name: string;
  sheetId: string;
  createdAt: string;
};

export type SheetGroupStatisticsView = {
  config: SheetConfigView;
  successfulGroupCount: number;
  totalSuccessfulAnswers: number;
  groups: Array<{
    groupNumber: number;
    successfulAnswerCount: number;
  }>;
};

export type ClassroomView = {
  id: string;
  code: string;
  name: string;
  status: string;
  createdAt?: string;
};

export type SubmittedAnswerView = {
  submissionId: string;
  questionId: string;
  userId: string;
  isOwnSubmission: boolean;
  displayName: string;
  answer: string;
  reviewStatus: string;
  groupNumber: number | "";
  enteredByTeacher: boolean;
  groupChallenged: boolean;
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
  settings: string
) {
  return request<{
    classroom: {
      id: string;
      code: string;
      name: string;
      status: string;
    };
  }>("/api/classrooms", {
    method: "POST",
    body: JSON.stringify({
      name,
      participationSettings: settings
    })
  }, token);
}

export async function listClassrooms(token: string) {
  return request<{ classrooms: ClassroomView[] }>("/api/classrooms", undefined, token);
}

export async function listAvailableClassrooms() {
  return request<{ classrooms: ClassroomView[] }>("/api/classrooms/available");
}

export async function deleteClassroom(token: string, classroomCode: string) {
  return request<{ deletedClassroomCode: string }>("/api/classrooms/delete", {
    method: "POST",
    body: JSON.stringify({ classroomCode })
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
  return request<{ deleted: string; deletedPackageCount: number }>("/api/sheet-configs/delete", {
    method: "POST",
    body: JSON.stringify({ configId })
  }, token);
}

export async function getSheetGroupStatistics(token: string, configId: string) {
  return request<SheetGroupStatisticsView>("/api/sheet-configs/statistics", {
    method: "POST",
    body: JSON.stringify({ configId })
  }, token);
}

export async function listSheetWorksheets(token: string, configId: string) {
  return request<{ configId: string; worksheets: string[] }>("/api/sheet-configs/worksheets", {
    method: "POST", body: JSON.stringify({ configId })
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
  return request<{
    members: Array<ClassroomParticipantContract & {
      presenceStatus: string;
      joinedAt: string;
      lastSeenAt: string;
      groupNumber: number;
      groupSuccessfulBuzzCount: number;
    }>;
  }>(
    `/api/classrooms/members?classroomCode=${encodeURIComponent(classroomCode)}`,
    undefined,
    token
  );
}

export async function resetAnswerLimits(token: string, classroomCode: string) {
  return request<{
    status: "ANSWER_LIMITS_RESET";
    resetGroupCount: number;
  }>("/api/classrooms/answer-limits/reset", {
    method: "POST",
    body: JSON.stringify({ classroomCode })
  }, token);
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

export async function createQuestionPackage(
  token: string,
  classroomCode: string,
  payload: CreateQuestionPackagePayload
) {
  return request<{ package: QuestionView }>("/api/question-packages", {
    method: "POST",
    body: JSON.stringify({ classroomCode, ...payload })
  }, token);
}

export async function listQuestionPackages(token: string, classroomCode: string) {
  return request<{ packages: QuestionView[] }>(
    `/api/question-packages?classroomCode=${encodeURIComponent(classroomCode)}`,
    undefined,
    token
  );
}

export async function scanGeneratedQuestions(token: string, payload: {
  classroomCode: string;
  sourceSheetConfigId: string;
  sourceWorksheet: string;
  answerSheetConfigId: string;
  answerWorksheet: string;
}) {
  return request<GeneratedQuestionScan>("/api/question-packages/scan", {
    method: "POST", body: JSON.stringify(payload)
  }, token);
}

export async function confirmGeneratedQuestions(token: string, scan: GeneratedQuestionScan) {
  return request<{ createdCount: number; packages: Array<{ id: string; title: string }> }>(
    "/api/question-packages/scan/confirm", {
      method: "POST", body: JSON.stringify({ scanToken: scan.scanToken, packages: scan.packages })
    }, token);
}

export async function updateQuestionPackageDescriptions(token: string, classroomCode: string,
  packageId: string, title: string, items: Array<{ itemId: string; prompt: string }>) {
  return request<{ package: QuestionView }>("/api/question-packages", {
    method: "PUT", body: JSON.stringify({ classroomCode, packageId, title, items })
  }, token);
}

export async function submitPackageAnswers(
  token: string,
  classroomCode: string,
  packageId: string,
  answers: Array<{ itemId: string; answer: string }>
) {
  return request<{
    accepted: boolean;
    outcomeStatus: string;
    submissionId?: string;
    syncStatus: string;
    groupNumber: number;
    groupSuccessfulBuzzCount: number;
  }>(
    "/api/question-packages/answers",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, packageId, answers })
    },
    token
  );
}

export async function updateQuestionState(token: string, classroomCode: string, questionId: string, targetState: string) {
  return request<{ question: QuestionView }>("/api/questions/state", {
    method: "POST",
    body: JSON.stringify({ classroomCode, questionId, targetState })
  }, token);
}

export async function finalizeBuzzReview(token: string, classroomCode: string, questionId: string) {
  return request<{
    questionId: string;
    roundId: string;
    outcomeStatus: string;
    winnerSubmissionId: string;
    winnerUserId: string;
    winnerGroupNumber: number;
    groupSuccessfulBuzzCount?: number;
    syncStatus: string;
    syncError: string;
  }>("/api/submissions/finalize-buzz", {
    method: "POST",
    body: JSON.stringify({ classroomCode, questionId })
  }, token);
}

export async function batchQuestionAction(
  token: string,
  classroomCode: string,
  action: "OPEN_ALL" | "STOP_ALL" | "RESET_ALL"
) {
  return request<{ classroomCode: string; action: string; results: QuestionBatchResult[] }>(
    "/api/questions/batch",
    {
      method: "POST",
      body: JSON.stringify({ classroomCode, action })
    },
    token
  );
}

export async function listQuestions(token: string, classroomCode: string) {
  return request<{ questions: QuestionView[] }>(
    `/api/questions?classroomCode=${encodeURIComponent(classroomCode)}`,
    undefined,
    token
  );
}

export async function getCurrentAnswers(token: string, classroomCode: string, questionId: string) {
  return request<{ classroomCode: string; questionId: string; answers: SubmittedAnswerView[] }>(
    `/api/submissions?classroomCode=${encodeURIComponent(classroomCode)}&questionId=${encodeURIComponent(questionId)}`,
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
  return request<{
    accepted: boolean;
    successful: boolean;
    outcomeStatus: string;
    requestId: string;
    receiptOrder: number;
    groupNumber: number;
    groupSuccessfulBuzzCount?: number;
  }>(
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
