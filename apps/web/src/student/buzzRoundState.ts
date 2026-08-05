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

export function canCancelBuzz(isWinner: boolean, hasSubmitted: boolean): boolean {
  return isWinner && !hasSubmitted;
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
