-- V5: Add last_response_body column to flow_steps
-- Stores the most recent successful HTTP response body for each step.
-- Updated by ExecutorService after every successful step execution.
-- Used by AssertionGeneratorService and SkipConditionGeneratorService
-- so the frontend never needs to send response bodies to LLM endpoints.
ALTER TABLE flow_steps
    ADD COLUMN IF NOT EXISTS last_response_body TEXT;