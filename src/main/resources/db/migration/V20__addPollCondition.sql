-- V15: Add poll_condition_json to flow_steps.
-- Extends polling from status-code-only to also support body field conditions.
-- e.g. poll until data.status = "COMPLETED" (regardless of, or in addition to, HTTP status).

ALTER TABLE flow_steps ADD COLUMN IF NOT EXISTS poll_condition_json TEXT;