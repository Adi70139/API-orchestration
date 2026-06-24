-- V14: Add performance_test_apis table (saved API configs per user) and
-- payload_list_json + api_id columns to performance_test_runs.

CREATE TABLE IF NOT EXISTS performance_test_apis (
                                                     id                  BIGSERIAL     PRIMARY KEY,
                                                     user_id             BIGINT        NOT NULL,
                                                     name                VARCHAR(255)  NOT NULL,
    description         TEXT,
    url                 TEXT          NOT NULL,
    method              VARCHAR(10)   NOT NULL,
    headers_json        TEXT,
    body_json           TEXT,
    payload_list_json   TEXT,
    created_at          TIMESTAMP     NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP
    );

CREATE INDEX IF NOT EXISTS idx_perf_api_user_id ON performance_test_apis (user_id);

-- Add api_id reference and payload_list_json snapshot to runs
ALTER TABLE performance_test_runs
    ADD COLUMN IF NOT EXISTS api_id           BIGINT,
    ADD COLUMN IF NOT EXISTS payload_list_json TEXT;