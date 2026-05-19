-- ============================================================
-- V1: Baseline schema — captures the original table structure
-- that was created by Hibernate ddl-auto before Flyway was added.
-- Flyway will record this as already applied on existing DBs
-- via the baseline mechanism (spring.flyway.baseline-on-migrate=true).
-- ============================================================

CREATE TABLE IF NOT EXISTS modules (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL UNIQUE,
    description             TEXT,
    default_environment_id  BIGINT
);

CREATE TABLE IF NOT EXISTS environments (
    id          BIGSERIAL PRIMARY KEY,
    name        VARCHAR(255) NOT NULL,
    module_id   BIGINT NOT NULL REFERENCES modules(id)
);

CREATE TABLE IF NOT EXISTS environment_variables (
    id              BIGSERIAL PRIMARY KEY,
    env_key         VARCHAR(255) NOT NULL,
    encrypted_value TEXT,
    environment_id  BIGINT NOT NULL REFERENCES environments(id)
);

CREATE TABLE IF NOT EXISTS flows (
    id                      BIGSERIAL PRIMARY KEY,
    name                    VARCHAR(255) NOT NULL,
    description             TEXT,
    module_id               BIGINT NOT NULL REFERENCES modules(id),
    default_environment_id  BIGINT REFERENCES environments(id),
    UNIQUE (name, module_id)
);

CREATE TABLE IF NOT EXISTS flow_steps (
    id              BIGSERIAL PRIMARY KEY,
    step_order      INTEGER NOT NULL,
    name            VARCHAR(255) NOT NULL,
    description     TEXT,
    method          VARCHAR(10) NOT NULL,
    url             TEXT NOT NULL,
    headers_json    TEXT,
    body_json       TEXT,
    assertions_json TEXT,
    flow_id         BIGINT NOT NULL REFERENCES flows(id)
);

CREATE TABLE IF NOT EXISTS module_executions (
    id          BIGSERIAL PRIMARY KEY,
    module_id   BIGINT NOT NULL REFERENCES modules(id),
    status      VARCHAR(50) NOT NULL,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS flow_executions (
    id                   BIGSERIAL PRIMARY KEY,
    flow_id              BIGINT NOT NULL REFERENCES flows(id),
    module_execution_id  BIGINT REFERENCES module_executions(id),
    status               VARCHAR(50) NOT NULL,
    started_at           TIMESTAMP,
    finished_at          TIMESTAMP
);

CREATE TABLE IF NOT EXISTS step_executions (
    id                      BIGSERIAL PRIMARY KEY,
    flow_execution_id       BIGINT NOT NULL REFERENCES flow_executions(id),
    step_id                 BIGINT NOT NULL,
    step_name               VARCHAR(255) NOT NULL,
    step_order              INTEGER NOT NULL,
    resolved_url            TEXT,
    resolved_headers_json   TEXT,
    resolved_body_json      TEXT,
    status_code             INTEGER,
    response_body           TEXT,
    success                 BOOLEAN NOT NULL,
    error_message           TEXT,
    duration_ms             BIGINT,
    assertion_results_json  TEXT
);

CREATE TABLE IF NOT EXISTS module_schedules (
    id          BIGSERIAL PRIMARY KEY,
    module_id   BIGINT NOT NULL UNIQUE REFERENCES modules(id),
    time        VARCHAR(10) NOT NULL,
    timezone    VARCHAR(100) NOT NULL,
    active      BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS bulk_jobs (
    id          BIGSERIAL PRIMARY KEY,
    type        VARCHAR(20) NOT NULL,
    status      VARCHAR(50) NOT NULL,
    started_at  TIMESTAMP,
    finished_at TIMESTAMP
);

CREATE TABLE IF NOT EXISTS bulk_job_items (
    id              BIGSERIAL PRIMARY KEY,
    bulk_job_id     BIGINT NOT NULL REFERENCES bulk_jobs(id),
    target_id       BIGINT NOT NULL,
    target_name     VARCHAR(255),
    status          VARCHAR(50) NOT NULL,
    execution_id    BIGINT,
    environment_id  BIGINT,
    duration_ms     BIGINT
);