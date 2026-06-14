-- V12: Add users table and ownership to modules

CREATE TABLE users (
                       id          BIGSERIAL PRIMARY KEY,
                       email       VARCHAR(255) NOT NULL UNIQUE,
                       name        VARCHAR(255),
                       password    VARCHAR(255),           -- bcrypt hash, null for OAuth users
                       role        VARCHAR(20)  NOT NULL DEFAULT 'USER',  -- USER | ADMIN
                       provider    VARCHAR(20)  NOT NULL DEFAULT 'LOCAL', -- LOCAL | GOOGLE
                       provider_id VARCHAR(255),           -- Google subject id, null for LOCAL
                       created_at  TIMESTAMP NOT NULL DEFAULT NOW()
);

-- Seed a default admin so the app is usable before any user registers
INSERT INTO users (email, name, password, role, provider)
VALUES ('admin@flowengine.local',
        'Admin',
           -- bcrypt of 'admin123' — change immediately in production
        '$2a$12$7Gg6MlHnbqGjRNB8FQOBv.1jqp3gRGBzV2dLRDq3cE.pW5UNSdOry',
        'ADMIN',
        'LOCAL');

-- Add owner column to modules (nullable so existing data is not broken)
ALTER TABLE modules ADD COLUMN IF NOT EXISTS created_by BIGINT
    REFERENCES users(id) ON DELETE SET NULL;