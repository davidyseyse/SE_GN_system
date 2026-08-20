import type { SubmittedAnswerView } from "../lib/api";

export function fastestDistinctGroupAnswers(
  answers: SubmittedAnswerView[],
  limit = 2
): SubmittedAnswerView[] {
  if (limit <= 0) return [];

  const selected: SubmittedAnswerView[] = [];
  const seenGroups = new Set<string>();
  for (const answer of answers) {
    const groupKey = typeof answer.groupNumber === "number" && answer.groupNumber > 0
      ? `group:${answer.groupNumber}`
      : `user:${answer.userId}`;
    if (seenGroups.has(groupKey)) continue;
    seenGroups.add(groupKey);
    selected.push(answer);
    if (selected.length === limit) break;
  }
  return selected;
}
