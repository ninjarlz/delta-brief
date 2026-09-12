-- java.time.Instant columns should be TIMESTAMPTZ, not TIMESTAMP (no time
-- zone) — TIMESTAMP is only correct as long as the JVM/DB session time
-- zone assumptions stay consistent; TIMESTAMPTZ removes that assumption
-- entirely. USING clauses assume the existing values are already UTC
-- (true today: both the app and Supabase run UTC).
ALTER TABLE users
    ALTER COLUMN verification_token_expires_at TYPE TIMESTAMPTZ USING verification_token_expires_at AT TIME ZONE 'UTC',
    ALTER COLUMN created_at TYPE TIMESTAMPTZ USING created_at AT TIME ZONE 'UTC';
