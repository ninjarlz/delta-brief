-- source_name is a denormalized text copy at ingest time, not an FK to
-- topic.sources — briefing is decoupled from topic's tables (see plan.md's
-- Critical Implementation Details), and a briefing keeps showing the source
-- name as it was at ingest time even if the source is later renamed.
CREATE TABLE ingested_items (
    id BIGSERIAL PRIMARY KEY,
    briefing_id BIGINT NOT NULL REFERENCES briefings(id) ON DELETE CASCADE,
    source_name VARCHAR(255) NOT NULL,
    title VARCHAR(1024) NOT NULL,
    link VARCHAR(2048) NOT NULL,
    published_at TIMESTAMPTZ,
    fetched_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX ingested_items_briefing_id_idx ON ingested_items (briefing_id);
