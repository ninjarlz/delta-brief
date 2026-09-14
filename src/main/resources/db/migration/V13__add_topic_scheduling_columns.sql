-- S-04: every topic gets a generation cadence. Existing topics are backfilled
-- to DAILY (an explicit product decision, not a conservative default — see
-- context/changes/scheduled-briefing-generation/plan.md) with next_due_at
-- anchored at their most recent briefing, falling back to created_at for a
-- topic that has none yet.
ALTER TABLE topics ADD COLUMN frequency VARCHAR(20) NOT NULL DEFAULT 'DAILY';
ALTER TABLE topics ADD COLUMN preferred_hour SMALLINT;
ALTER TABLE topics ADD COLUMN next_due_at TIMESTAMPTZ;
ALTER TABLE topics ADD COLUMN last_scheduled_attempt_at TIMESTAMPTZ;
ALTER TABLE topics ADD COLUMN last_scheduled_status VARCHAR(20);

ALTER TABLE topics ADD CONSTRAINT topics_preferred_hour_range
    CHECK (preferred_hour IS NULL OR (preferred_hour >= 0 AND preferred_hour <= 23));

UPDATE topics t
SET next_due_at = COALESCE(
    (SELECT MAX(b.generated_at) FROM briefings b WHERE b.topic_id = t.id),
    t.created_at
) + INTERVAL '1 day';

-- Backs the scheduler's poll query (S-04): topics due for a scheduled run,
-- excluding MANUAL topics which never carry a next_due_at value to match.
CREATE INDEX topics_next_due_at_idx ON topics (next_due_at) WHERE frequency <> 'MANUAL';
