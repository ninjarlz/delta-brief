-- ON DELETE CASCADE (unlike topics.user_id in V7, which has none): topic
-- deletion is a real, already-shipped feature (TopicController.deleteTopic),
-- so a topic with briefings must be deletable too — cascading here keeps
-- that working instead of failing on this FK once any briefing exists.
CREATE TABLE briefings (
    id BIGSERIAL PRIMARY KEY,
    topic_id BIGINT NOT NULL REFERENCES topics(id) ON DELETE CASCADE,
    type VARCHAR(20) NOT NULL,
    generated_at TIMESTAMPTZ NOT NULL,
    key_changes TEXT NOT NULL,
    trend_continuation TEXT NOT NULL,
    noise_speculation TEXT NOT NULL,
    significance TEXT NOT NULL,
    uncertainties TEXT NOT NULL,
    source_impact TEXT NOT NULL
);

-- Backs both "latest briefing for a topic" and the inline history list.
CREATE INDEX briefings_topic_id_generated_at_idx ON briefings (topic_id, generated_at DESC);
