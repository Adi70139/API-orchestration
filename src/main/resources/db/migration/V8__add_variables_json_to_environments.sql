ALTER TABLE environments
    ADD COLUMN IF NOT EXISTS variables_json TEXT;