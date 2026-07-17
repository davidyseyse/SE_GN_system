# Deployment and Rollback

## Runtime Services

- `api`: Java HTTP API on `8080`
- `websocket`: Java WebSocket transport on `8081`
- `postgres`: PostgreSQL 16 on `5432`
- `redis`: Redis 7 on `6379`
- `web`: Vite-built frontend assets

## Pre-Deploy Checks

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

1. Bring up PostgreSQL and Redis with `docker compose up -d postgres redis`
2. Build frontend with `npm.cmd run build --workspace @classroom/web`
3. Build API classes with `npm.cmd run build --workspace @classroom/api`
4. Start API process with `npm.cmd run start --workspace @classroom/api`
5. Publish frontend assets from `apps/web/dist`
6. Verify:
   - `GET /api/health`
   - WebSocket handshake to `WEBSOCKET_URL`
   - Teacher token issuance
   - Classroom creation
   - Student join

## Operational Checks

- `GET /api/health` returns `status=ok`
- `GET /api/realtime/config` returns both HTTP fallback paths and WebSocket URL
- `GET /api/leaderboard?classroomCode=<code>` returns ranked data
- `GET /api/reports/export?classroomCode=<code>&type=scores` returns CSV payload
- PostgreSQL `audit_logs` contains `EXPORT_GENERATED`

## Rollback

1. Stop the API process
2. Restore the previous API build artifact or previous source deployment
3. Restore previous frontend assets
4. Restart API
5. Re-check `/api/health` and WebSocket handshake
6. If a schema-level issue caused the rollback:
   - stop API writes
   - restore PostgreSQL from the most recent snapshot
   - clear Redis keys for active buzz locks if they are stale
   - restart API and verify classroom creation, join, and leaderboard endpoints
