-- V13: User feedback / issue reporting
CREATE TABLE feedback (
                          id          BIGSERIAL PRIMARY KEY,
                          user_id     BIGINT REFERENCES users(id) ON DELETE SET NULL,
                          type        VARCHAR(30)  NOT NULL DEFAULT 'BUG',  -- BUG | FEATURE_REQUEST | GENERAL
                          title       VARCHAR(255) NOT NULL,
                          description TEXT         NOT NULL,
                          severity    VARCHAR(20)  DEFAULT 'MEDIUM',         -- LOW | MEDIUM | HIGH | CRITICAL
                          status      VARCHAR(20)  NOT NULL DEFAULT 'OPEN',  -- OPEN | IN_REVIEW | RESOLVED | CLOSED
                          page_url    VARCHAR(500),  -- which page the user was on when they reported
                          user_agent  VARCHAR(500),  -- browser info, auto-captured
                          created_at  TIMESTAMP NOT NULL DEFAULT NOW(),
                          updated_at  TIMESTAMP NOT NULL DEFAULT NOW()
);