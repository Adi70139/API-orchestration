-- V16: Add last_method_outputs_json to flow_steps.
-- Stores flattened method output keys from the most recent execution so
-- poll-fields can include them alongside response body fields.

ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS last_method_outputs_json TEXT;