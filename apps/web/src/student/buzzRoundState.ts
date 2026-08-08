export function buzzInteractionKey(questionId: string, roundId: string): string {
  return `${questionId}:${roundId}`;
}

export function realtimeEventApplies(
  eventType: string,
  eventRoundId: string,
  currentRoundId: string
): boolean {
  return eventType === "BUZZ_RESET" || eventRoundId === currentRoundId;
}

export function timeoutCancellationNotice(
  eventType: string,
  reason: string | undefined,
  eventRoundId: string,
  currentRoundId: string
): string | null {
  return eventType === "BUZZ_CANCELLED"
    && reason === "TIMEOUT"
    && eventRoundId === currentRoundId
    ? "作答時間已到，已重新開放搶答。"
    : null;
}

export function remainingAnswerSeconds(
  answerDeadlineAt: string | undefined,
  nowMs: number
): number | null {
  if (!answerDeadlineAt) {
    return null;
  }
  const deadlineMs = Date.parse(answerDeadlineAt);
  if (!Number.isFinite(deadlineMs)) {
    return null;
  }
  return Math.max(0, Math.ceil((deadlineMs - nowMs) / 1000));
}

export function canCancelBuzz(isWinner: boolean, hasSubmitted: boolean): boolean {
  return isWinner && !hasSubmitted;
}

export function removeInteractionEntry<T>(
  entries: Record<string, T>,
  interactionKey: string
): Record<string, T> {
  const next = { ...entries };
  delete next[interactionKey];
  return next;
}

export function removeQuestionRoundKeys(keys: string[], questionId: string): string[] {
  return keys.filter((key) => !key.startsWith(`${questionId}:`));
}

export function mergeSubmittedInteractionKeys(
  currentKeys: string[],
  authoritativeKeys: string[]
): string[] {
  return [...new Set([...currentKeys, ...authoritativeKeys])];
}

export function ownSubmittedInteractionKeys(
  questions: Array<Record<string, unknown>>,
  answersByQuestionId: Record<string, Array<{ isOwnSubmission: boolean }>>
): string[] {
  return questions.flatMap((question) => {
    const questionId = String(question.id);
    const hasOwnSubmission = (answersByQuestionId[questionId] ?? [])
      .some((answer) => answer.isOwnSubmission);
    return hasOwnSubmission
      ? [buzzInteractionKey(questionId, String(question.roundId ?? "0"))]
      : [];
  });
}
