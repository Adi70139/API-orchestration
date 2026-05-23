-- V4: Add skip_condition_json column to flow_steps
-- Stores conditional skip logic evaluated against accumulated step responses at execution time.
-- JSON structure: { "logic": "AND"|"OR", "conditions": [ { "path": "...", "operator": "...", "value": "..." } ] }
ALTER TABLE flow_steps
    ADD COLUMN IF NOT EXISTS skip_condition_json TEXT;