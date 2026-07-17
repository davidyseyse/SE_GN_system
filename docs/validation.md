# Validation Commands

## Automated Validation

- `npm.cmd run test --workspace @classroom/api`

This runs:

- Java smoke verification
- authorization rejection checks
- question state transition checks
- duplicate submission protection checks
- buzz winner uniqueness checks
- leaderboard and export verification
- audit log verification

## Load Verification

- `npm.cmd run test:load --workspace @classroom/api`

This runs:

- 100 concurrent student join requests
- 50 concurrent buzz attempts
- WebSocket reconnect and resync verification

## Frontend Build Verification

- `npm.cmd run build --workspace @classroom/shared`
- `npm.cmd run build --workspace @classroom/web`
