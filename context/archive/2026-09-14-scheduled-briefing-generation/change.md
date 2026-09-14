---
change_id: scheduled-briefing-generation
title: Scheduled briefing generation
status: archived
created: 2026-09-14
updated: 2026-09-14
archived_at: 2026-09-14T15:00:31Z
---

## Notes

Deferred idea (blocked on FR-012/S-06, email delivery, which doesn't exist yet): once email delivery ships, send the user an email when a scheduled briefing generation fails ("this briefing wasn't generated due to a technical issue, the next one will be attempted automatically"), and escalate to the administrator's email if failures for a topic keep recurring. Not built in this change — v1 only surfaces failure state in the app (topic page indicator).

Plan deviation, confirmed intentional (found during `/10x-impl-review`, 2026-09-14): the plan's Phase 2 contract for `TopicService.updateSchedule` said to recompute `next_due_at` from "the topic's current last-generation anchor," matching `createTopic`'s rule. The shipped implementation instead anchors the recompute at edit-time (`Instant.now()`) — editing a topic's schedule resets its cadence from the moment of the edit rather than preserving the remaining time in its current cycle. Kept as-is: this is the more intuitive behavior for an edit action and avoids the edge case where recomputing from a stale last-generation anchor could make a topic immediately overdue right after an edit.

Post-closeout addendum (found during `/10x-impl-review`, 2026-09-14): commit `e10beba` (landed after this plan's Phase 4 closeout commit `c640e29`) added test coverage for two previously-untested behaviors surfaced by review: topic deletion cascading to delete its briefings (`ON DELETE CASCADE`, V8), and `TopicRepositoryAdapter.recordSuccessfulGeneration`/`recordFailedScheduledGeneration` silently no-opping when the scheduler dispatches against a topic deleted between its due-query and dispatch. Both are real, previously-uncovered gaps; no production code changed. Recorded here since `plan.md`'s `## Progress` section predates this commit.
