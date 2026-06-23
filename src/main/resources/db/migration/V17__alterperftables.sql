-- V12: Performance test runs no longer reference step_id from the main app.
-- The perf service is now standalone — users provide URL/method/headers/body directly.
-- Rename step_name to name (user-provided label), drop step_id column.

ALTER TABLE performance_test_runs RENAME COLUMN step_name TO name;
ALTER TABLE performance_test_runs DROP COLUMN IF EXISTS step_id;