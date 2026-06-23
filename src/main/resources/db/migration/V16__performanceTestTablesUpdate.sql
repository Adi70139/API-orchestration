-- V11: Fix percentile column names to match Hibernate's naming strategy.
-- Hibernate converts p50LatencyMs → p50latency_ms (no underscore before digits),
-- but V10 created them as p50_latency_ms. Rename to match what Hibernate expects.

ALTER TABLE performance_test_runs RENAME COLUMN p50_latency_ms TO p50latency_ms;
ALTER TABLE performance_test_runs RENAME COLUMN p95_latency_ms TO p95latency_ms;
ALTER TABLE performance_test_runs RENAME COLUMN p99_latency_ms TO p99latency_ms;