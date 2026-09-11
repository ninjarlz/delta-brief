CREATE INDEX idx_users_verification_token ON users (verification_token)
    WHERE verification_token IS NOT NULL;
