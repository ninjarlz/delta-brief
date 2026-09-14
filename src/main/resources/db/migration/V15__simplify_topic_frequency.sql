-- Frequency is simplified to DAILY / EVERY_OTHER_DAY / WEEKLY only: MANUAL
-- (no schedule at all) and TWICE_DAILY (broken by ScheduleCalculator's
-- preferred-hour nudge, which silently collapsed it into once-daily) are
-- both removed. Existing rows are backfilled to DAILY.

-- a) Flip legacy frequency values to DAILY.
UPDATE topics SET frequency = 'DAILY' WHERE frequency IN ('MANUAL', 'TWICE_DAILY');

-- b) Backfill next_due_at only for rows that had none — structurally, that
--    was only ever true for former-MANUAL rows (TWICE_DAILY always had a
--    real next_due_at). Reuses V13's exact backfill formula. Must run after
--    (a), since next_due_at IS NULL is only a correct "was MANUAL" signal
--    once (a) has already flipped those rows' frequency.
UPDATE topics t
SET next_due_at = COALESCE(
    (SELECT MAX(b.generated_at) FROM briefings b WHERE b.topic_id = t.id),
    t.created_at
) + INTERVAL '1 day'
WHERE t.next_due_at IS NULL;

-- c) The partial predicate `WHERE frequency <> 'MANUAL'` no longer means
--    anything once MANUAL can't exist and every next_due_at is non-null
--    going forward. Rebuild as a plain index.
DROP INDEX topics_next_due_at_idx;
CREATE INDEX topics_next_due_at_idx ON topics (next_due_at);
