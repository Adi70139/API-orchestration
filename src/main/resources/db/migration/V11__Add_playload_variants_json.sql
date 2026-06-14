-- V11: Add payload_variants_json to flow_steps
-- Used when HAR/Postman import finds multiple distinct request bodies for the
-- same method+URL endpoint. Instead of creating N duplicate steps, all variant
-- bodies are stored here as a JSON array: [{"name": "...", "bodyJson": "..."}].
-- bodyJson on the step itself remains the active/default payload (first variant).

ALTER TABLE flow_steps
    ADD COLUMN IF NOT EXISTS payload_variants_json TEXT;