# Scheduled Briefing Generation — Plan Brief

> Full plan: `context/changes/scheduled-briefing-generation/plan.md`

## What & Why

Topics currently only get a fresh briefing when a user manually clicks "Generate now." This adds automatic, unattended generation on a per-topic cadence (FR-008/FR-009) so users get delta briefings without remembering to ask for them.

## Starting Point

`Topic` has no scheduling fields today; `BriefingService.generateBriefing(TopicId, UserId)` already does the actual generation work and is directly callable from a non-HTTP context. No `@Scheduled`/`@EnableScheduling` exists anywhere — only `@EnableAsync` (email sending) as a config-class precedent. The next Flyway migration slot is `V13`.

## Desired End State

Every topic has a frequency (manual / twice-daily / daily / every-other-day / weekly) and an optional preferred hour. A background job checks every 15 minutes for due topics and generates briefings for them, retrying failed topics on the next tick forever (no auto-pause). The topic page shows the next expected run and flags the last scheduled attempt if it failed. A manual "Generate now" click resets the schedule exactly like a scheduled run would.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Timing model | Relative interval per topic (`last generated_at + frequency`) + optional preferred-hour nudge | Simple per-topic math; the hour nudge gives users some control without a full cron UI |
| Poll interval | Every 15 minutes, single `@Scheduled(fixedRate)` job | Matches the infra decision (in-process on the always-on Render instance) and the "customizable to some extent" ask |
| Default frequency | `DAILY`, applied to new topics AND backfilled onto all existing topics | Explicit choice to enable automatic generation broadly, not opt-in |
| Failure handling | Skip and let the next 15-min tick retry naturally (no in-cycle backoff) | Matches existing partial-failure tolerance philosophy in `BriefingService` |
| Failure visibility | Show failure state in the app (no email yet) | Email delivery (FR-012/S-06) doesn't exist yet — building failure email ahead of success email is scope creep; deferred as a note in `change.md` |
| Auto-pause | None — retries indefinitely | Avoids inventing a "paused" topic state/recovery UI not in the PRD |
| Concurrency | Bounded parallelism, small worker pool | Keeps wall-clock bounded under a large due-batch, without exceeding the tight 5-connection Hikari pool |
| Cost cap | None for v1 | Adding a cap now means deciding degrade behavior with no spec for it |
| Manual-trigger interaction | Manual generation resets the schedule (same rule as scheduled) | One consistent rule everywhere: `next_due_at` = last successful generation + interval |
| Frequency UI | Set at creation, editable later from the topic page | Matches how other topic fields already work; covers wanting to dial back a noisy topic |
| Schedule visibility | Show next/last run on the topic page | Directly answers "is this actually working" without checking logs |
| Testing approach | Both: pure unit tests on due-computation + a thinner integration test on the poll wiring | Fast deterministic coverage of the math, plus real verification the `@Scheduled` wiring actually works |

## Scope

**In scope:** frequency + preferred-hour fields, due-date calculation, the poll job, bounded concurrent dispatch, failure visibility in the UI, manual-trigger schedule reset, creation + edit UI.

**Out of scope:** per-user timezones, email notifications on failure, auto-pause after repeated failures, a global daily generation cap, any change to per-source fetch failure handling.

## Architecture / Approach

Schema + a pure `ScheduleCalculator` function first (fully unit-testable, no Spring), then the topic creation/edit UI and visibility, then the `@Scheduled` poll job in `briefing.application` (same module that already owns generation) which reuses `BriefingService.generateBriefing` and a shared schedule-advance step that both the scheduler and the existing manual-trigger path call.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model & pure scheduling logic | Schema (`V13`) + `Frequency`/`ScheduleCalculator` domain code, fully unit-tested | Getting the hour-nudge edge cases (rollover, exact-match) right |
| 2. Topic creation/edit UI + visibility | Frequency/hour fields at creation and via edit; next-run/failure indicator on topic pages | Edit endpoint following existing ownership-check conventions correctly |
| 3. The scheduler + manual-trigger integration | `@Scheduled` poll job, bounded dispatch, shared schedule-advance rule wired into manual generation too | Keeping concurrency within the 5-connection Hikari pool; not breaking the existing manual-generation flow |
| 4. Documentation & roadmap closeout | Roadmap sync, deferred-idea note recorded | None — process only |

**Prerequisites:** None beyond what's already merged (S-01–S-03 done).
**Estimated effort:** ~3-4 implementation sessions across 3 code phases + 1 closeout phase.

## Open Risks & Assumptions

- Preferred-hour nudging is computed in UTC — the app has no per-user timezone concept anywhere today, so this is a real (documented) limitation, not an oversight.
- Backfilling all existing topics to `DAILY` is a deliberate, disclosed-tradeoff choice (not conservative) — it will noticeably increase LLM call volume immediately after this ships.
- The onboarding-briefing trigger point wasn't traced in this plan (topic creation doesn't call `generateBriefing` directly) — Phase 3's shared schedule-advance step is designed to work regardless of which caller triggers a successful generation, so this doesn't block the plan, but the implementer should confirm the actual onboarding call site when writing Phase 3.

## Success Criteria (Summary)

- A topic set to a given frequency gets a new briefing automatically, on roughly the expected cadence, without any manual action.
- A failing topic is visibly flagged in the app and keeps retrying every cycle rather than silently going quiet forever.
- Manually generating a briefing doesn't cause a near-duplicate scheduled briefing minutes later.
