-- V15: Add prerequisite_chain_json to performance_test_apis and performance_test_runs.
-- Stores ordered list of APIs to run before the target (e.g. login to get a token)
-- with field capture mappings. Each virtual user runs its own prereq chain so
-- concurrent tests don't share sessions.

ALTER TABLE performance_test_apis
    ADD COLUMN IF NOT EXISTS prerequisite_chain_json TEXT;

ALTER TABLE performance_test_runs
    ADD COLUMN IF NOT EXISTS prerequisite_chain_json TEXT;