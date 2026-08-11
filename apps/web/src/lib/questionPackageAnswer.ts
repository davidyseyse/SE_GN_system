import type { QuestionItemView } from "./api";

export function parsePackageAnswers(answer: string): Record<string, string> | null {
  try {
    const parsed: unknown = JSON.parse(answer);
    if (!parsed || Array.isArray(parsed) || typeof parsed !== "object") {
      return null;
    }
    return Object.fromEntries(
      Object.entries(parsed as Record<string, unknown>).map(([itemId, value]) => [
        itemId,
        value == null ? "" : String(value)
      ])
    );
  } catch {
    return null;
  }
}

export function formatPackageAnswer(answer: string, items: QuestionItemView[]): string {
  const answers = parsePackageAnswers(answer);
  if (!answers) {
    return answer;
  }
  return items
    .map((item) => `${item.prompt}:${answers[item.id] ?? ""}`)
    .join(",");
}

export function formatTeacherAnswerAttribution(
  displayName: string,
  groupNumber: number | "",
  enteredByTeacher: boolean,
  groupChallenged: boolean
): string {
  const challenge = groupNumber === "" ? "挑戰：未指定組別" : `挑戰：第${groupNumber}組`;
  if (enteredByTeacher) return challenge;
  return groupChallenged ? `${displayName}　${challenge}` : displayName;
}
