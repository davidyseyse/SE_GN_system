## Why

教師端已支援將多道題目同時設為 `BUZZ_OPEN`，但學生端搶答大廳以單一 `activeQuestion` 呈現，只會顯示第一筆符合條件的題目。這導致多題並行搶答時，學生無法看見或參與其餘開放中的題目，與課堂「多題同時搶答」的使用情境不符。

## What Changes

- 修正學生端搶答大廳，改為列出所有目前處於可互動狀態（`BUZZ_OPEN`、`ANSWER_OPEN`、`LOCKED`、`PUBLISHED`）的題目，而非只顯示第一題。
- 將搶答成功者、作答草稿、提交狀態等 UI 狀態改為以 `questionId` 為鍵的 per-question 管理，避免多題並行時狀態互相覆蓋。
- 依既有 WebSocket 事件（含 `payload.questionId`）為每題獨立計算搶答結果與互動可用性。
- 補充學生端多題並行搶答的驗收情境與回歸測試，確保單題與多題流程皆正常。

## Capabilities

### New Capabilities

- None.

### Modified Capabilities

- `realtime-buzzing`: 明確要求學生端在同時存在多道開放搶答題時，必須顯示並可操作每一道開放中的題目。
- `realtime-answer-submission`: 明確要求學生可在多道並行開放題目中，分別提交答案且互不干擾。

## Impact

- Affected UI: `apps/web/src/student/StudentPlayPage.tsx`（主要）、必要時的樣式調整。
- Affected APIs: 無需變更；既有 `listQuestions`、搶答與作答 API 已支援 per-question 操作。
- Affected real-time systems: 無需變更事件契約；前端需正確依 `questionId` 分派既有 `BUZZ_RESULT`、`BUZZ_CANCELLED`、`BUZZ_RESET` 等事件。
- Affected tests: 建議新增或擴充前端/整合驗證，覆蓋「多題同時 `BUZZ_OPEN`」顯示與互動情境。
