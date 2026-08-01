export const roles = ["TEACHER", "STUDENT", "ASSISTANT"] as const;
export type Role = (typeof roles)[number];

export const questionTypes = [
  "SINGLE_CHOICE",
  "MULTIPLE_CHOICE",
  "TRUE_FALSE",
  "SHORT_ANSWER",
  "BUZZ"
] as const;
export type QuestionType = (typeof questionTypes)[number];

export const questionStates = [
  "DRAFT",
  "READY",
  "BUZZ_OPEN",
  "ANSWER_OPEN",
  "LOCKED",
  "REVIEW",
  "PUBLISHED",
  "CLOSED"
] as const;
export type QuestionState = (typeof questionStates)[number];

export const buzzStates = ["IDLE", "OPEN", "LOCKED", "RESOLVED"] as const;
export type BuzzState = (typeof buzzStates)[number];

export const realtimeEventTypes = [
  "PRESENCE_UPDATED",
  "SESSION_RESYNCED",
  "QUESTION_STATE_CHANGED",
  "BUZZ_STATE_CHANGED",
  "SUBMISSION_CREATED",
  "SUBMISSION_UPDATED",
  "SUBMISSION_DELETED",
  "SUBMISSION_REVIEWED"
] as const;
export type RealtimeEventType = (typeof realtimeEventTypes)[number];

export interface ClassroomParticipantContract {
  id: string;
  displayName: string;
  role: Role;
  connected: boolean;
}

export interface ClassroomQuestionContract {
  id: string;
  type: QuestionType;
  state: QuestionState;
  prompt: string;
  score: number;
}

export interface RealtimeEnvelopeContract<TPayload> {
  type: RealtimeEventType;
  classroomId: string;
  occurredAt: string;
  payload: TPayload;
}
