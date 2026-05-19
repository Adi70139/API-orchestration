-- ============================================================
-- V2: Add retry, polling, and execution history columns
-- Added as part of retry/polling feature implementation.
-- All columns use IF NOT EXISTS to be safe on partial deployments.
-- ============================================================

-- flow_steps: retry config
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS retry_count      INTEGER NOT NULL DEFAULT 0;
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS retry_delay_ms   INTEGER NOT NULL DEFAULT 1000;
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS initial_delay_ms INTEGER NOT NULL DEFAULT 0;

-- flow_steps: polling config
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS poll_until_success   BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS poll_interval_ms     INTEGER NOT NULL DEFAULT 5000;
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS poll_max_attempts    INTEGER NOT NULL DEFAULT 10;
ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS poll_expected_status INTEGER NOT NULL DEFAULT 200;

-- step_executions: retry tracking
ALTER TABLE step_executions ADD COLUMN IF NOT EXISTS retry_attempts_json TEXT;
ALTER TABLE step_executions ADD COLUMN IF NOT EXISTS total_attempts      INTEGER NOT NULL DEFAULT 1;

-- step_executions: poll tracking
ALTER TABLE step_executions ADD COLUMN IF NOT EXISTS poll_attempts_json TEXT;
ALTER TABLE step_executions ADD COLUMN IF NOT EXISTS total_poll_attempts INTEGER;
ALTER TABLE step_executions ADD COLUMN IF NOT EXISTS polling_timed_out  BOOLEAN NOT NULL DEFAULT FALSE;

-- Fix existing rows that may have nulls from before these columns existed
UPDATE step_executions SET total_attempts = 1 WHERE total_attempts IS NULL;
UPDATE flow_steps SET retry_count      = 0    WHERE retry_count IS NULL;
UPDATE flow_steps SET retry_delay_ms   = 1000 WHERE retry_delay_ms IS NULL;
UPDATE flow_steps SET initial_delay_ms = 0    WHERE initial_delay_ms IS NULL;
UPDATE flow_steps SET poll_until_success   = FALSE WHERE poll_until_success IS NULL;
UPDATE flow_steps SET poll_interval_ms     = 5000  WHERE poll_interval_ms IS NULL;
UPDATE flow_steps SET poll_max_attempts    = 10    WHERE poll_max_attempts IS NULL;
UPDATE flow_steps SET poll_expected_status = 200   WHERE poll_expected_status IS NULL;