-- Phase 6: user accounts and revocable API tokens.
-- Passwords are stored only as BCrypt hashes; tokens only as SHA-256 hashes, so a database leak
-- does not reveal usable credentials.

CREATE TABLE users (
    id            UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    username      VARCHAR(64) NOT NULL UNIQUE CHECK (username ~ '^[a-z0-9][a-z0-9._-]{2,63}$'),
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(16) NOT NULL CHECK (role IN ('USER', 'ADMIN')),
    disabled      BOOLEAN     NOT NULL DEFAULT false,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE api_tokens (
    token_hash CHAR(64)    PRIMARY KEY CHECK (token_hash ~ '^[0-9a-f]{64}$'),
    user_id    UUID        NOT NULL REFERENCES users (id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    expires_at TIMESTAMPTZ NOT NULL,
    revoked_at TIMESTAMPTZ
);
CREATE INDEX ix_api_tokens_user ON api_tokens (user_id);
CREATE INDEX ix_api_tokens_expiry ON api_tokens (expires_at) WHERE revoked_at IS NULL;

-- Objects uploaded before authentication existed belonged to the placeholder owner 'local-dev'.
-- They move to the bootstrap administrator account (default username 'admin').
UPDATE objects SET owner_id = 'admin' WHERE owner_id = 'local-dev';
UPDATE upload_sessions SET owner_id = 'admin' WHERE owner_id = 'local-dev';
