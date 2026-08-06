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
  active_question_id UUID NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  opened_at TIMESTAMPTZ NULL,
  closed_at TIMESTAMPTZ NULL
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
  state VARCHAR(20) NOT NULL DEFAULT 'READY',
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
  round_number INTEGER NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE,
  answer_deadline_at TIMESTAMPTZ NULL,
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
  round_number INTEGER NOT NULL DEFAULT 0,
  UNIQUE (question_id, user_id, attempt_number)
);

CREATE TABLE IF NOT EXISTS question_buzz_rounds (
  question_id UUID PRIMARY KEY REFERENCES questions(id) ON DELETE CASCADE,
  current_round INTEGER NOT NULL DEFAULT 0,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

ALTER TABLE buzz_records ADD COLUMN IF NOT EXISTS round_number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE buzz_records ADD COLUMN IF NOT EXISTS active BOOLEAN NOT NULL DEFAULT TRUE;
ALTER TABLE buzz_records ADD COLUMN IF NOT EXISTS answer_deadline_at TIMESTAMPTZ NULL;
ALTER TABLE submissions ADD COLUMN IF NOT EXISTS round_number INTEGER NOT NULL DEFAULT 0;
ALTER TABLE questions ALTER COLUMN state SET DEFAULT 'READY';

DO $$
DECLARE
  migrated_draft_count INTEGER;
BEGIN
  UPDATE questions
  SET state = 'READY', updated_at = NOW()
  WHERE state = 'DRAFT';
  GET DIAGNOSTICS migrated_draft_count = ROW_COUNT;
  RAISE NOTICE 'Migrated % legacy DRAFT question(s) to READY', migrated_draft_count;
END $$;

ALTER TABLE submissions DROP CONSTRAINT IF EXISTS submissions_question_id_user_id_attempt_number_key;
CREATE UNIQUE INDEX IF NOT EXISTS idx_submissions_round_attempt
  ON submissions (question_id, user_id, round_number, attempt_number);

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

DROP INDEX IF EXISTS idx_buzz_records_single_winner;
CREATE UNIQUE INDEX IF NOT EXISTS idx_buzz_records_single_active_winner
  ON buzz_records (question_id, round_number)
  WHERE successful = TRUE AND active = TRUE;

CREATE INDEX IF NOT EXISTS idx_buzz_records_overdue_active_winner
  ON buzz_records (answer_deadline_at, question_id, round_number)
  WHERE successful = TRUE AND active = TRUE AND answer_deadline_at IS NOT NULL;

CREATE INDEX IF NOT EXISTS idx_submissions_lookup
  ON submissions (question_id, user_id, submitted_at DESC);

CREATE INDEX IF NOT EXISTS idx_score_records_lookup
  ON score_records (classroom_id, user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_logs_classroom_created_at
  ON audit_logs (classroom_id, created_at DESC);

-- Leaderboard display has been retired; these migrations are safe to repeat.
DROP TABLE IF EXISTS leaderboard_snapshots;
ALTER TABLE classrooms DROP COLUMN IF EXISTS leaderboard_visibility;

-- Question packages retain questions.id as the lifecycle and buzz aggregate.
ALTER TABLE questions
  ADD COLUMN IF NOT EXISTS sheet_config_id UUID NULL REFERENCES sheet_configs(id);

CREATE TABLE IF NOT EXISTS question_items (
  id UUID PRIMARY KEY DEFAULT gen_random_uuid(),
  question_id UUID NOT NULL REFERENCES questions(id) ON DELETE CASCADE,
  ordinal INTEGER NOT NULL,
  prompt TEXT NOT NULL,
  target_cell TEXT NULL,
  normalized_target_cell TEXT GENERATED ALWAYS AS (UPPER(BTRIM(target_cell))) STORED,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (question_id, ordinal),
  UNIQUE (question_id, normalized_target_cell),
  CHECK (ordinal > 0),
  CHECK (BTRIM(prompt) <> '')
);

ALTER TABLE submissions
  ADD COLUMN IF NOT EXISTS sheet_sync_status VARCHAR(20) NOT NULL DEFAULT 'NOT_REQUIRED',
  ADD COLUMN IF NOT EXISTS sheet_sync_error TEXT NULL,
  ADD COLUMN IF NOT EXISTS sheet_synced_at TIMESTAMPTZ NULL;

DO $$
DECLARE
  backfilled_item_count INTEGER;
  converted_submission_count INTEGER;
  invalid_binding_count INTEGER;
BEGIN
  UPDATE questions q
  SET sheet_config_id =
    CASE
      WHEN COALESCE(q.publication_settings->>'sheetConfigId', '') ~
           '^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[1-5][0-9a-fA-F]{3}-[89abAB][0-9a-fA-F]{3}-[0-9a-fA-F]{12}$'
       AND EXISTS (
         SELECT 1 FROM sheet_configs sc
         WHERE sc.id = (q.publication_settings->>'sheetConfigId')::uuid
       )
      THEN (q.publication_settings->>'sheetConfigId')::uuid
      ELSE q.sheet_config_id
    END
  WHERE q.sheet_config_id IS NULL;

  INSERT INTO question_items (id, question_id, ordinal, prompt, target_cell)
  SELECT gen_random_uuid(), q.id, 1, q.prompt,
         NULLIF(BTRIM(q.publication_settings->>'targetCell'), '')
  FROM questions q
  WHERE NOT EXISTS (
    SELECT 1 FROM question_items qi WHERE qi.question_id = q.id
  );
  GET DIAGNOSTICS backfilled_item_count = ROW_COUNT;

  UPDATE submissions s
  SET submitted_answer = jsonb_build_object(qi.id::text, s.submitted_answer)
  FROM question_items qi
  WHERE qi.question_id = s.question_id
    AND qi.ordinal = 1
    AND jsonb_typeof(s.submitted_answer) <> 'object';
  GET DIAGNOSTICS converted_submission_count = ROW_COUNT;

  SELECT COUNT(*) INTO invalid_binding_count
  FROM questions q
  JOIN question_items qi ON qi.question_id = q.id
  WHERE q.sheet_config_id IS NULL OR qi.target_cell IS NULL;

  RAISE NOTICE
    'Question package migration: % item(s) backfilled, % submission(s) converted, % legacy package(s) need binding correction',
    backfilled_item_count, converted_submission_count, invalid_binding_count;
END $$;

CREATE INDEX IF NOT EXISTS idx_question_items_question_order
  ON question_items (question_id, ordinal);

CREATE INDEX IF NOT EXISTS idx_submissions_sheet_sync
  ON submissions (sheet_sync_status)
  WHERE sheet_sync_status = 'RETRYABLE';
