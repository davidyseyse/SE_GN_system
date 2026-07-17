export declare const roles: readonly ["TEACHER", "STUDENT", "ASSISTANT"];
export type Role = (typeof roles)[number];
export declare const questionTypes: readonly ["SINGLE_CHOICE", "MULTIPLE_CHOICE", "TRUE_FALSE", "SHORT_ANSWER", "BUZZ"];
export type QuestionType = (typeof questionTypes)[number];
export declare const questionStates: readonly ["DRAFT", "READY", "BUZZ_OPEN", "ANSWER_OPEN", "LOCKED", "REVIEW", "PUBLISHED", "CLOSED"];
export type QuestionState = (typeof questionStates)[number];
export declare const buzzStates: readonly ["IDLE", "OPEN", "LOCKED", "RESOLVED"];
export type BuzzState = (typeof buzzStates)[number];
export declare const leaderboardVisibilityLevels: readonly ["HIDDEN", "TOP_3", "TOP_10", "FULL"];
export type LeaderboardVisibility = (typeof leaderboardVisibilityLevels)[number];
export declare const realtimeEventTypes: readonly ["PRESENCE_UPDATED", "SESSION_RESYNCED", "QUESTION_STATE_CHANGED", "BUZZ_STATE_CHANGED", "LEADERBOARD_UPDATED"];
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
