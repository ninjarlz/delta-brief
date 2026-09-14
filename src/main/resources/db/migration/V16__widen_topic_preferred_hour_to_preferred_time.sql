-- Widen preferred_hour (whole-hour-only) to preferred_time (full HH:MM
-- precision) — the topic form now uses a native <input type="time">, which
-- inherently offers minute precision, so the stored value honors it instead
-- of silently truncating.
ALTER TABLE topics ADD COLUMN preferred_time TIME;

UPDATE topics SET preferred_time = make_time(preferred_hour, 0, 0) WHERE preferred_hour IS NOT NULL;

-- TIME inherently restricts to valid times of day — no replacement CHECK
-- constraint needed for the old topics_preferred_hour_range.
ALTER TABLE topics DROP CONSTRAINT topics_preferred_hour_range;
ALTER TABLE topics DROP COLUMN preferred_hour;
