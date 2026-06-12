-- V10: Add UI automation script storage and flow type marker
-- playwright_script stores the generated Playwright Java test (can be several KB)
-- flow_type marks the flow as 'API' (default) or 'UI' for frontend display

ALTER TABLE flows
    ADD COLUMN IF NOT EXISTS playwright_script TEXT,
    ADD COLUMN IF NOT EXISTS flow_type VARCHAR(10) DEFAULT 'API';