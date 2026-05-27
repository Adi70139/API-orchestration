-- V7: Custom methods and step method attachments

CREATE TABLE IF NOT EXISTS custom_methods (
                                              id                        BIGSERIAL PRIMARY KEY,
                                              name                      VARCHAR(255) NOT NULL,
    description               TEXT,
    type                      VARCHAR(50)  NOT NULL, -- BUILTIN | USER_DEFINED
    builtin_type              VARCHAR(50),            -- RANDOM_NUMBER | RANDOM_UUID | TIMESTAMP | STRING_CONCAT | DB_QUERY
    parameter_definitions_json TEXT,                 -- JSON array of {name, type, description, required}
    groovy_script             TEXT,                  -- USER_DEFINED only
    llm_prompt_description    TEXT,                  -- original description sent to LLM
    global                    BOOLEAN NOT NULL DEFAULT FALSE,
    created_at                TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at                TIMESTAMP
    );

CREATE TABLE IF NOT EXISTS step_methods (
                                            id                      BIGSERIAL PRIMARY KEY,
                                            step_id                 BIGINT NOT NULL REFERENCES flow_steps(id) ON DELETE CASCADE,
    method_id               BIGINT NOT NULL REFERENCES custom_methods(id) ON DELETE CASCADE,
    execution_order         INTEGER NOT NULL DEFAULT 1,
    parameter_bindings_json TEXT    -- JSON map of {paramName: value or {placeholder}}
    );

CREATE INDEX IF NOT EXISTS idx_step_methods_step_id ON step_methods(step_id);
CREATE INDEX IF NOT EXISTS idx_custom_methods_global ON custom_methods(global);

-- Seed builtin methods so they appear in the method picker immediately
INSERT INTO custom_methods (name, description, type, builtin_type, parameter_definitions_json, global)
VALUES
    (
        'Random Number',
        'Generates a random integer between min and max (inclusive). Output: {method.result}',
        'BUILTIN', 'RANDOM_NUMBER',
        '[{"name":"min","type":"number","description":"Lower bound (inclusive)","required":true},
          {"name":"max","type":"number","description":"Upper bound (inclusive)","required":true}]',
        TRUE
    ),
    (
        'Random UUID',
        'Generates a random UUID v4. Output: {method.result}',
        'BUILTIN', 'RANDOM_UUID',
        '[]',
        TRUE
    ),
    (
        'Current Timestamp',
        'Returns the current date/time formatted as specified. Output: {method.result}',
        'BUILTIN', 'TIMESTAMP',
        '[{"name":"format","type":"string","description":"Java date format pattern e.g. yyyy-MM-dd or yyyy-MM-dd HH:mm:ss","required":false}]',
        TRUE
    ),
    (
        'String Concat',
        'Joins a comma-separated list of values with an optional separator. Output: {method.result}',
        'BUILTIN', 'STRING_CONCAT',
        '[{"name":"values","type":"string","description":"Comma-separated values to join","required":true},
          {"name":"separator","type":"string","description":"String to join with (default: empty string)","required":false}]',
        TRUE
    ),
    (
        'DB Query',
        'Connects to a database and runs a SELECT query. Returns a random row. Each column is available as {method.columnName}.',
        'BUILTIN', 'DB_QUERY',
        '[{"name":"connectionString","type":"string","description":"JDBC connection URL e.g. jdbc:postgresql://host:5432/db","required":true},
          {"name":"username","type":"string","description":"Database username","required":true},
          {"name":"password","type":"string","description":"AES-encrypted database password (use /methods/encrypt-password)","required":true},
          {"name":"query","type":"string","description":"SELECT query to execute (SELECT only)","required":true}]',
        TRUE
    );