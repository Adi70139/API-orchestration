-- V13: Add user_id to performance_test_runs for user-scoped data isolation.
-- The perf service now validates JWTs directly (same secret as main app) and stores
-- userId from the token claim on every run. Existing rows get userId=0 as a placeholder.

ALTER TABLE performance_test_runs ADD COLUMN IF NOT EXISTS user_id BIGINT NOT NULL DEFAULT 0;

CREATE INDEX IF NOT EXISTS idx_perf_runs_user_id ON performance_test_runs (user_id);