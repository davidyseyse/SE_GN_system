## Context

教師端 `TeacherDashboardPage` 已以 `questions.map()` 列出所有題目，並可將多道題目各自切換至 `BUZZ_OPEN`。後端 `QuestionStateMachine` 亦以「每題獨立狀態」運作，搶答 API 與 WebSocket 事件皆帶 `questionId`，可支援多題並行搶答。

問題出在學生端 `StudentPlayPage.tsx`：它以 `questions.find(...)` 只取第一筆可互動題目作為 `activeQuestion`，並將搶答成功者、答案輸入、提交狀態等全部綁在這一題上。因此當多題同時開放時，其餘題目雖存在於 API 回傳清單中，卻不會出現在 UI。

```
目前（錯誤）                         預期（正確）
─────────────────                   ─────────────────
questions: [Q1, Q2, Q3]              questions: [Q1, Q2, Q3]
   BUZZ_OPEN  BUZZ_OPEN  READY           BUZZ_OPEN  BUZZ_OPEN  READY
       │                                    │          │
       ▼                                    ▼          ▼
  find() → Q1 only                    filter() → [Q1, Q2]
  UI 顯示 1 題                        UI 顯示 2 題卡片
```

## Goals / Non-Goals

**Goals:**

- 學生端同時顯示所有可互動題目（`BUZZ_OPEN`、`ANSWER_OPEN`、`LOCKED`、`PUBLISHED`）。
- 每題擁有獨立的搶答按鈕狀態、得標者顯示、答案輸入與提交狀態。
- 沿用既有 REST / WebSocket 契約，不修改後端。
- 維持單題搶答流程的既有行為（回歸相容）。

**Non-Goals:**

- 不改變教師端多題控制流程。
- 不新增「一次只能開一題」的後端限制。
- 不重做整個學生端路由或 realtime hook 架構。
- 不處理題目排序/分頁 UX（除非列表過長需最小化樣式調整）。

## Decisions

### 1. 以 `filter` 取代 `find` 取得可互動題目清單

將 `activeQuestion`（單一物件）改為 `activeQuestions`（陣列），篩選條件維持現有 state 集合。陣列排序沿用 `listQuestions` 回傳順序（與教師端題目排序一致）。

替代方案：仍只顯示一題但提供題目切換器。這無法滿足「同時看見所有開放題」的需求，故不採用。

### 2. 將 UI 狀態改為以 `questionId` 為鍵

| 現狀（單值） | 調整後（per-question） |
|---|---|
| `locallyWonQuestionId: string` | `locallyWonQuestionIds: Set<string>` 或 `Record<string, boolean>` |
| `answer: string` | `answersByQuestionId: Record<string, string>` |
| `submittedQuestionIds: string[]` | 保留（已支援多題） |
| `buzzWinnerUserId`（全域推導） | `buzzWinnerByQuestionId: Record<string, string>`，由 realtime events 依 `payload.questionId` 分組計算 |

`buzzWinnerByQuestionId` 應在 `useMemo` 中一次遍歷 `realtime.events`，建立 `questionId → winnerUserId` 映射，避免在 render loop 中重複掃描。

### 3. 抽取 `StudentQuestionCard` 子元件（或內联 map）

將每題的搶答/作答 UI 從單一區塊改為 `activeQuestions.map(question => ...)`。每張卡片接收：

- `question`
- `winnerUserId`（該題）
- `canBuzz` / `isWinner` / `isLoser`（依該題計算）
- `answer` / `hasSubmitted`（依該題）
- 事件 handler（帶 `questionId`）

這能隔離 per-question 邏輯，降低 `StudentPlayPage` 複雜度。

### 4. 不修改後端與事件契約

既有事件已包含 `payload.questionId`（見 `StudentPlayPage` 的 `RealtimeEventView`）。前端修正即可，無需新 API 或新事件類型。

### 5. 驗證策略

- 手動：教師同時開放 2+ 題 → 學生端應看見全部 → 分別搶答/作答互不干擾。
- 自動：若專案尚無前端測試框架，至少在 `validation.md` 或 API smoke 流程中新增「多題 list + buzz」整合步驟；若可補最小 UI 測試則優先覆蓋 filter 邏輯。

## Risks / Trade-offs

- [多題卡片過長影響手機 UX] → 以垂直堆疊卡片呈現，保留題幹摘要；必要時加最小 spacing，本次不引入分頁。
- [notice 全域提示混淆] → 搶答/提交結果改以卡片內局部提示為主，全域 `notice` 僅保留連線/會話訊息。
- [realtime 事件順序影響 winner 推導] → 維持現有「最後一筆成功 BUZZ_RESULT 覆蓋」邏輯，但 scope 限於各 `questionId`。
- [回歸單題流程] → 實作後需驗證單題搶答、取消鎖定、提交答案路徑未受影響。

## Migration Plan

1. 重構 `StudentPlayPage` 狀態結構（per-question maps）。
2. 改 UI 為多題卡片列表。
3. 手動驗證多題/單題情境。
4. 更新驗證文件或測試清單。

回滾：還原 `StudentPlayPage.tsx`（及相關樣式）即可，無資料遷移。

## Open Questions

- 可互動題目是否需依特定欄位排序（例如 `sortOrder`）？預設沿用 API 回傳順序。
- `LOCKED` / `PUBLISHED` 題目是否一律顯示在搶答大廳？目前維持現有 filter 條件不變，避免擴大 scope。
