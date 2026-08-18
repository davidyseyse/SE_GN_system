# Deployment and Rollback

## Runtime Services

Google Sheet 統計需要 `GOOGLE_APPLICATION_CREDENTIALS` 指向有效服務帳戶憑證，且目標試算表已分享給該服務帳戶。API 更新後必須重新啟動 Java 服務，否則舊程序不會載入 `/api/sheet-configs/statistics` 路由。

- `api`: Java HTTP API on `8080`
- `websocket`: Java WebSocket transport on `8081`
- `postgres`: PostgreSQL 16 on `5432`
- `redis`: Redis 7 on `6379`
- `web`: Vite-built frontend assets

## Pre-Deploy Checks

Deleting a Sheet configuration transactionally removes every question package that uses it as a source or answer binding, including cascaded submissions, buzz records, and scores. Back up PostgreSQL before using this destructive operation.

1. Run `npm.cmd run build --workspace @classroom/shared`
2. Run `npm.cmd run build --workspace @classroom/web`
3. Run `npm.cmd run build --workspace @classroom/api`
4. Run `npm.cmd run test --workspace @classroom/api`
5. Run `npm.cmd run test:load --workspace @classroom/api`
6. Verify `.env` or deployment environment includes:
   - `API_PORT`
   - `WEBSOCKET_PORT`
   - `DATABASE_URL`
   - `REDIS_URL`
   - `JWT_SECRET`
   - `JWT_ISSUER`
   - `JWT_AUDIENCE`
   - `WEBSOCKET_URL`

## Deployment Flow

1. Snapshot PostgreSQL and record the count of `questions.state = 'DRAFT'`.
2. Bring up PostgreSQL and Redis with `docker compose up -d postgres redis`. The idempotent initialization migration changes legacy `DRAFT` questions to `READY` and emits the migrated row count as a PostgreSQL notice.
3. Build frontend with `npm.cmd run build --workspace @classroom/web`
4. Build API classes with `npm.cmd run build --workspace @classroom/api`
5. Start API process with `npm.cmd run start --workspace @classroom/api`
6. Publish frontend assets from `apps/web/dist`
7. Verify:
   - `GET /api/health`
   - WebSocket handshake to `WEBSOCKET_URL`
   - Teacher token issuance
   - Classroom creation
   - Student join

## Operational Checks

- `GET /api/health` returns `status=ok`
- `GET /api/realtime/config` returns both HTTP fallback paths and WebSocket URL
- `GET /api/submissions?classroomCode=<code>&questionId=<id>` returns current answers to authenticated classroom members
- `GET /api/reports/export?classroomCode=<code>&type=scores` returns CSV payload
- PostgreSQL `audit_logs` contains `EXPORT_GENERATED`
- PostgreSQL has no unintended `DRAFT` questions after migration
- Batch responses are monitored for `FAILED` items and unexpected skip reasons
- `OPEN_ALL` retries do not increment rounds for already-open questions
- no `(group_number, success_date)` row in `global_group_buzz_counts` exceeds the fixed daily global limit of `1`
- each buzz round has at most one `submissions.buzz_winner = TRUE` row
- new buzz records have no individual deadline, and no automatic timeout cancellation events are emitted
- Redis contains only round receipt-sequence keys; no `buzz:winner:*` key is created

## Rollback

1. Stop the API process
2. Restore the previous API build artifact or previous source deployment
3. Restore previous frontend assets
4. Restart API
5. Re-check `/api/health` and WebSocket handshake
6. If a schema-level issue caused the rollback:
   - stop API writes
   - restore PostgreSQL from the most recent snapshot
   - clear stale `buzz:sequence:*` keys only for explicitly identified inactive rounds
   - restart API and verify classroom creation, join, current-answer visibility, score detail, and reporting endpoints

The `DRAFT` migration is intentionally one-way for questions that have subsequently advanced. Restore a migrated row to `DRAFT` only when its identifier was captured before deployment and it is still `READY` with no post-migration round or interaction activity; otherwise retain its current lifecycle state.

## Open Buzz and Success-Limit Migration

Apply `infra/postgres/init.sql` before deploying the new API. The idempotent migration keys `global_group_buzz_counts` by group number and Asia/Taipei calendar date, migrates existing counts using their last-update date, retains the legacy classroom-group table, backfills completed historical winners by group number and submission date, and creates a partial unique index enforcing one winner submission per question round.

Every classroom uses the fixed system-wide daily group limit of `1`, including existing classrooms; participation settings cannot override it and the teacher UI hides the field. The active date changes automatically at Asia/Taipei midnight without a cleanup job. Buzz attempts no longer enforce legacy `answerTimeLimitSeconds` or `answer_deadline_at` values; they remain active until submission, explicit cancellation, or round reset/closure. During rollout, monitor `NOT_FASTEST`, `NO_ACTIVE_ATTEMPT`, `WINNER_ALREADY_SUBMITTED`, `SUCCESS_LIMIT_REACHED`, and `sheet_sync_status = 'RETRYABLE'` separately.

For rollback, deploy the previous API and web artifacts while retaining the additive columns and indexes. Historical success counts and winner markers are audit data and must not be deleted. The former API may recreate Redis winner keys; clear only keys for precisely identified inactive rounds after rollback verification.
