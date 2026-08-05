import type { QuestionView } from "../lib/api";

export type TeacherRealtimeEvent = {
  type: string;
  payload?: Record<string, unknown> & {
    questionId?: string;
    packageId?: string;
    roundId?: string;
    state?: string;
    successful?: boolean;
    userId?: string;
    externalUserId?: string;
    displayName?: string;
    status?: string;
    reviewStatus?: string;
  };
};

export function applyTeacherBuzzEvent(
  questions: QuestionView[],
  event: TeacherRealtimeEvent | null
): QuestionView[] {
  if (!event?.payload) {
    return questions;
  }
  const payload = event.payload;
  const questionId = String(payload.packageId ?? payload.questionId ?? "");
  if (!questionId) {
    return questions;
  }

  return questions.map((question) => {
    if (String(question.id) !== questionId) {
      return question;
    }
    const eventRoundId = String(payload.roundId ?? "");
    const currentRoundId = String(question.roundId ?? "0");
    const opensNewRound = event.type === "QUESTION_STATE_CHANGED"
      && payload.state === "BUZZ_OPEN";
    if (eventRoundId && eventRoundId !== currentRoundId && !opensNewRound) {
      return question;
    }

    if (event.type === "BUZZ_RESULT" && payload.successful) {
      return {
        ...question,
        buzzStatus: "WINNER_ANSWERING",
        winnerUserId: String(payload.userId ?? ""),
        winnerExternalUserId: String(payload.externalUserId ?? ""),
        winnerDisplayName: String(payload.displayName ?? ""),
        submissionStatus: "",
        reviewStatus: ""
      };
    }
    if (event.type === "SUBMISSION_CREATED") {
      return {
        ...question,
        buzzStatus: "SUBMITTED_PENDING_REVIEW",
        winnerUserId: String(payload.userId ?? question.winnerUserId ?? ""),
        winnerExternalUserId: String(payload.externalUserId ?? question.winnerExternalUserId ?? ""),
        winnerDisplayName: String(payload.displayName ?? question.winnerDisplayName ?? ""),
        submissionStatus: String(payload.status ?? "RECEIVED"),
        reviewStatus: String(payload.reviewStatus ?? "")
      };
    }
    if (event.type === "BUZZ_CANCELLED") {
      return {
        ...question,
        buzzStatus: "WAITING_FOR_BUZZ",
        winnerUserId: "",
        winnerExternalUserId: "",
        winnerDisplayName: "",
        submissionStatus: "",
        reviewStatus: ""
      };
    }
    if (event.type === "BUZZ_RESET") {
      return {
        ...question,
        state: "READY",
        buzzStatus: "NOT_OPEN",
        winnerUserId: "",
        winnerExternalUserId: "",
        winnerDisplayName: "",
        submissionStatus: "",
        reviewStatus: ""
      } as QuestionView;
    }
    if (event.type === "QUESTION_STATE_CHANGED" && payload.state) {
      const state = String(payload.state);
      return {
        ...question,
        state,
        roundId: eventRoundId || currentRoundId,
        buzzStatus: state === "LOCKED"
          ? "STOPPED"
          : state === "BUZZ_OPEN"
            ? "WAITING_FOR_BUZZ"
            : "NOT_OPEN"
      } as QuestionView;
    }
    return question;
  });
}
