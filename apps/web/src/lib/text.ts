export function formatRealtimeStatus(status: string) {
  switch (status) {
    case "idle":
      return "尚未連線";
    case "connecting":
      return "連線中";
    case "connected":
      return "已連線";
    case "error":
      return "連線異常";
    default:
      return status;
  }
}

export function formatQuestionState(state: string) {
  switch (state) {
    case "DRAFT":
      return "未開放";
    case "READY":
      return "未開放";
    case "BUZZ_OPEN":
      return "開放搶答";
    case "ANSWER_OPEN":
      return "作答中";
    case "LOCKED":
      return "已鎖定";
    case "REVIEW":
      return "待批改";
    case "PUBLISHED":
      return "已公布";
    case "CLOSED":
      return "已關閉";
    default:
      return state;
  }
}

export function formatPresenceStatus(status: string) {
  switch (status) {
    case "ONLINE":
      return "在線";
    case "OFFLINE":
      return "離線";
    case "CONNECTED":
      return "已連線";
    case "DISCONNECTED":
      return "已斷線";
    default:
      return status;
  }
}

export function formatLeaderboardVisibility(value: string) {
  switch (value) {
    case "HIDDEN":
      return "隱藏";
    case "TOP_3":
      return "前 3 名";
    case "TOP_10":
      return "前 10 名";
    case "FULL":
      return "完整榜單";
    default:
      return value;
  }
}

export function formatQuestionType(value: string) {
  switch (value) {
    case "SHORT_ANSWER":
      return "簡答題";
    case "SINGLE_CHOICE":
      return "單選題";
    case "MULTIPLE_CHOICE":
      return "多選題";
    case "TRUE_FALSE":
      return "是非題";
    case "BUZZ":
      return "搶答題";
    default:
      return value;
  }
}

export function formatRealtimeEventType(value: string) {
  switch (value) {
    case "SESSION_RESYNCED":
    case "session.resynced":
      return "同步完成";
    case "QUESTION_STATE_CHANGED":
      return "題目狀態更新";
    case "QUESTION_OPENED":
      return "題目已開啟";
    case "BUZZ_OPENED":
      return "搶答已開放";
    case "BUZZ_RESULT":
      return "搶答結果";
    case "BUZZ_CANCELLED":
      return "已取消鎖定";
    case "BUZZ_RESET":
      return "已重置搶答";
    case "SUBMISSION_RECEIVED":
      return "收到作答";
    case "LEADERBOARD_UPDATED":
      return "排行榜已更新";
    case "PRESENCE_UPDATED":
      return "在線狀態已更新";
    default:
      return value;
  }
}
