-- Optional, per PRD FR-004 (originally parked as v2 scope: "does anyone
-- fill in optional fields in v1? If the AI doesn't use it, it's a dead
-- field"). Unparked once briefing generation (S-03) existed to actually
-- consume it — the user's stated observation goal now feeds directly into
-- the generation prompt as extra context.
ALTER TABLE topics ADD COLUMN description TEXT;
