## Why

課堂互動常同時需要點名、出題、搶答、計分與結果回饋，但多數做法分散在不同工具，無法保證即時同步與搶答結果一致性。現在需要一套可直接支援課堂互動流程的即時系統，讓老師能在單一介面控制題目節奏，並讓學生以手機或電腦穩定參與搶答與作答。

## What Changes

- 新增課堂建立、加入、成員在線狀態與課堂控制流程。
- 新增題庫管理與題目狀態流轉，支援單選、多選、是非、簡答與搶答題。
- 新增即時搶答機制，以伺服器判定唯一第一搶答者並保留完整排序紀錄。
- 新增即時作答機制，支援限時提交、自動鎖定、客觀題自動批改與簡答題人工批改。
- 新增即時統計、分數計算、排行榜與課後報表匯出。
- 新增教師端與學生端分流 UI，以及對應 REST API、WebSocket 事件與權限控管。
- 新增斷線重連、狀態補同步與稽核紀錄，以提升一致性與可維運性。

## Capabilities

### New Capabilities

- `classroom-session-management`: 管理課堂建立、加入、成員名單、在線狀態與教師控制台流程。
- `question-lifecycle-management`: 管理題庫、題型、題目排序、題目狀態機與發布流程。
- `realtime-buzzing`: 提供即時搶答、唯一得標判定、排序紀錄、取消與重啟機制。
- `realtime-answer-submission`: 提供限時作答、答案提交、鎖定、批改與提交狀態同步。
- `scoring-and-leaderboard`: 提供計分規則、排行榜、課堂成績統計與個人成績查詢。
- `reporting-and-export`: 提供課後統計查詢、搶答紀錄與 CSV/Excel 匯出。

### Modified Capabilities

- None.

## Impact

- Affected specs: new specs for classroom session, question lifecycle, realtime buzzing, realtime answer submission, scoring/leaderboard, and reporting/export.
- Affected APIs: authentication, classroom management, question management, buzz actions, answer submission, scoring, leaderboard, reporting.
- Affected real-time systems: WebSocket event contracts, reconnection handling, and session resync behavior.
- Affected data model: users, classrooms, members, questions, options, buzz records, submissions, score records, leaderboard snapshots, and audit logs.
- Affected dependencies: WebSocket infrastructure, persistent relational storage, and Redis-backed concurrency control for buzz ordering.
