-- S-06: per-topic opt-in for emailing newly generated briefings (FR-012).
-- Existing topics are backfilled to opted-in (true) — a deliberate product
-- default, matching V13's own precedent of choosing a real default over a
-- conservative placeholder for existing rows.
ALTER TABLE topics ADD COLUMN email_enabled BOOLEAN NOT NULL DEFAULT true;
