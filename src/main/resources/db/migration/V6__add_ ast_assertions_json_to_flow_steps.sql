-- V6: Add last_assertions_json to flow_steps
-- Stores the cumulative assertion JSON built up via the assertion generator.
-- Always merged (never replaced wholesale). Separate from assertions_json
-- which is the runtime-evaluated version saved explicitly by the user.
ALTER TABLE flow_steps
    ADD COLUMN IF NOT EXISTS last_assertions_json TEXT;