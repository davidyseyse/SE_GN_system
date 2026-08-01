## 1. Student state refactor

- [x] 1.1 Replace `activeQuestion` (`questions.find`) with `activeQuestions` (`questions.filter`) using the existing interactive state set
- [x] 1.2 Build `buzzWinnerByQuestionId` from realtime events keyed by `payload.questionId`
- [x] 1.3 Convert `locallyWonQuestionId` and `answer` to per-question maps while keeping `submittedQuestionIds` behavior

## 2. Multi-question student UI

- [x] 2.1 Render an interactive question card for each entry in `activeQuestions`
- [x] 2.2 Scope buzz, cancel-lock, answer input, and submit handlers to the selected `questionId`
- [x] 2.3 Adjust student lobby layout/styles so multiple question cards remain readable on mobile

## 3. Validation

- [x] 3.1 Document the multi-question student verification scenarios in `docs/validation.md`
