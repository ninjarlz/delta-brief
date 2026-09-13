CREATE TABLE topics (
    id BIGSERIAL PRIMARY KEY,
    user_id BIGINT NOT NULL REFERENCES users(id),
    name VARCHAR(255) NOT NULL,
    category_id BIGINT NOT NULL REFERENCES categories(id),
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

-- Case-insensitive per-user uniqueness enforced at the DB level (not just an
-- application-side check-then-insert, which would race under concurrent
-- requests).
CREATE UNIQUE INDEX topics_user_id_lower_name_uk ON topics (user_id, LOWER(name));
