-- V10: Performance testing tables for the perf-service.
-- Both services share the same DB; migrations live in the main app since
-- it owns the Flyway history table (flyway_schema_history).

CREATE TABLE IF NOT EXISTS performance_test_runs (
                                                     id                        BIGSERIAL PRIMARY KEY,
                                                     step_id                   BIGINT        NOT NULL,
                                                     step_name                 VARCHAR(255)  NOT NULL,
    resolved_url              TEXT          NOT NULL,
    resolved_method           VARCHAR(10)   NOT NULL,
    resolved_headers_json     TEXT,
    resolved_body_json        TEXT,
    test_type                 VARCHAR(20)   NOT NULL,

    -- Config
    virtual_users             INT           NOT NULL,
    duration_seconds          INT,
    stress_ramp_step          INT,
    stress_ramp_interval_seconds INT,
    spike_users               INT,
    spike_duration_seconds    INT,
    warmup_seconds            INT,
    cooldown_seconds          INT,
    soak_duration_seconds     INT,

    -- Status
    status                    VARCHAR(20)   NOT NULL,
    started_at                TIMESTAMP     NOT NULL,
    completed_at              TIMESTAMP,

    -- Summary stats
    total_requests            BIGINT,
    successful_requests       BIGINT,
    failed_requests           BIGINT,
    error_rate_percent        DOUBLE PRECISION,
    avg_latency_ms            DOUBLE PRECISION,
    min_latency_ms            DOUBLE PRECISION,
    max_latency_ms            DOUBLE PRECISION,
    p50_latency_ms            DOUBLE PRECISION,
    p95_latency_ms            DOUBLE PRECISION,
    p99_latency_ms            DOUBLE PRECISION,
    throughput_rps            DOUBLE PRECISION,
    stress_breaking_point_users INT,
    error_summary             TEXT,

    created_at                TIMESTAMP     NOT NULL DEFAULT NOW()
    );

CREATE TABLE IF NOT EXISTS performance_test_samples (
                                                        id                BIGSERIAL PRIMARY KEY,
                                                        run_id            BIGINT        NOT NULL,
                                                        virtual_user_id   INT,
                                                        fired_at          TIMESTAMP     NOT NULL,
                                                        latency_ms        BIGINT        NOT NULL,
                                                        status_code       INT           NOT NULL,
                                                        success           BOOLEAN       NOT NULL,
                                                        error_detail      VARCHAR(500),
    concurrent_users  INT
    );

CREATE INDEX IF NOT EXISTS idx_sample_run_id  ON performance_test_samples (run_id);
CREATE INDEX IF NOT EXISTS idx_sample_fired_at ON performance_test_samples (fired_at);