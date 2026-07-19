CREATE EXTENSION IF NOT EXISTS "pgcrypto";

CREATE TABLE IF NOT EXISTS users (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  external_id VARCHAR(100) NOT NULL UNIQUE,
  display_name VARCHAR(120) NOT NULL,
  role VARCHAR(20) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  CHECK (role IN ('TEACHER', 'STUDENT', 'ASSISTANT'))
);

CREATE TABLE IF NOT EXISTS classrooms (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  teacher_id UUID NOT NULL REFERENCES users(id),
  classroom_code VARCHAR(12) NOT NULL UNIQUE,
  name VARCHAR(200) NOT NULL,
  status VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  participation_settings JSONB NOT NULL DEFAULT '{}'::jsonb,
  leaderboard_visibility VARCHAR(20) NOT NULL DEFAULT 'HIDDEN',
  active_question_id UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  opened_at TIMESTAMPTZ NULL,
  closed_at TIMESTAMPTZ NULL,
  CHECK (leaderboard_visibility IN ('HIDDEN', 'TOP_3', 'TOP_10', 'FULL'))
);

CREATE TABLE IF NOT EXISTS sheet_configs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  name VARCHAR(200) NOT NULL,
  sheet_id VARCHAR(200) NOT NULL UNIQUE,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS classroom_members (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  role VARCHAR(20) NOT NULL,
  presence_status VARCHAR(20) NOT NULL DEFAULT 'OFFLINE',
  joined_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  last_seen_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (classroom_id, user_id),
  CHECK (role IN ('TEACHER', 'STUDENT', 'ASSISTANT'))
);

CREATE TABLE IF NOT EXISTS questions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  type VARCHAR(32) NOT NULL,
  state VARCHAR(20) NOT NULL DEFAULT 'DRAFT',
  prompt TEXT NOT NULL,
  answer_key JSONB NOT NULL DEFAULT '{}'::jsonb,
  publication_settings JSONB NOT NULL DEFAULT '{}'::jsonb,
  score INTEGER NOT NULL DEFAULT 0,
  answer_time_limit_seconds INTEGER NULL,
  created_by UUID NOT NULL REFERENCES users(id),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  archived_at TIMESTAMPTZ NULL,
  UNIQUE (classroom_id, ordinal),
  CHECK (type IN ('SINGLE_CHOICE', 'MULTIPLE_CHOICE', 'TRUE_FALSE', 'SHORT_ANSWER', 'BUZZ')),
  CHECK (state IN ('DRAFT', 'READY', 'BUZZ_OPEN', 'ANSWER_OPEN', 'LOCKED', 'REVIEW', 'PUBLISHED', 'CLOSED'))
);

DO $$
BEGIN
  IF NOT EXISTS (
    SELECT 1
    FROM pg_constraint
    WHERE conname = 'classrooms_active_question_fk'
  ) THEN
    ALTER TABLE classrooms
      ADD CONSTRAINT classrooms_active_question_fk
      FOREIGN KEY (active_question_id) REFERENCES questions(id);
  END IF;
END $$;

CREATE TABLE IF NOT EXISTS question_options (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  label VARCHAR(40) NOT NULL,
  content TEXT NOT NULL,
  is_correct BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (question_id, ordinal)
);

CREATE TABLE IF NOT EXISTS buzz_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  request_id VARCHAR(100) NOT NULL,
  receipt_order BIGINT NOT NULL,
  received_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  outcome_status VARCHAR(20) NOT NULL,
  successful BOOLEAN NOT NULL DEFAULT FALSE,
  UNIQUE (question_id, request_id)
);

CREATE TABLE IF NOT EXISTS submissions (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  attempt_number INTEGER NOT NULL DEFAULT 1,
  submitted_answer JSONB NOT NULL,
  submission_status VARCHAR(20) NOT NULL DEFAULT 'RECEIVED',
  is_correct BOOLEAN NULL,
  awarded_score INTEGER NULL,
  review_status VARCHAR(20) NULL,
  submitted_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  reviewed_at TIMESTAMPTZ NULL,
  UNIQUE (question_id, user_id, attempt_number)
);

CREATE TABLE IF NOT EXISTS score_records (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  user_id UUID NOT NULL REFERENCES users(id) ON DELETE CASCADE,
  question_id UUID NULL REFERENCES questions(id) ON DELETE SET NULL,
  source_type VARCHAR(30) NOT NULL,
  delta_score INTEGER NOT NULL,
  running_total INTEGER NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (classroom_id, user_id, question_id, source_type, created_at)
);

CREATE TABLE IF NOT EXISTS leaderboard_snapshots (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NOT NULL REFERENCES classrooms(id) ON DELETE CASCADE,
  snapshot_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  ranking_data JSONB NOT NULL,
  visibility VARCHAR(20) NOT NULL,
  UNIQUE (classroom_id, snapshot_at),
  CHECK (visibility IN ('HIDDEN', 'TOP_3', 'TOP_10', 'FULL'))
);

CREATE TABLE IF NOT EXISTS audit_logs (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  classroom_id UUID NULL REFERENCES classrooms(id) ON DELETE SET NULL,
  actor_user_id UUID NULL REFERENCES users(id) ON DELETE SET NULL,
  action_type VARCHAR(60) NOT NULL,
  target_type VARCHAR(60) NOT NULL,
  target_id UUID NULL,
  metadata JSONB NOT NULL DEFAULT '{}'::jsonb,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_classroom_members_presence
  ON classroom_members (classroom_id, presence_status);

CREATE INDEX IF NOT EXISTS idx_questions_state
  ON questions (classroom_id, state);

CREATE INDEX IF NOT EXISTS idx_buzz_records_question_order
  ON buzz_records (question_id, receipt_order, received_at);

CREATE UNIQUE INDEX IF NOT EXISTS idx_buzz_records_single_winner
  ON buzz_records (question_id)
  WHERE successful = TRUE;

CREATE INDEX IF NOT EXISTS idx_submissions_lookup
  ON submissions (question_id, user_id, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_score_records_lookup
  ON score_records (classroom_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_leaderboard_snapshots_latest
  ON leaderboard_snapshots (classroom_id, snapshot_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_classroom_created_at
  ON audit_logs (classroom_id, created_at DESC);
