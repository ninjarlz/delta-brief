<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Scheduled Briefing Generation

- **Plan**: context/changes/scheduled-briefing-generation/plan.md
- **Scope**: Phase 1 of 4 (full plan — all phases complete)
- **Date**: 2026-09-14
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 4 warnings, 4 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | WARNING |

## Findings

### F1 — Phase 4 roadmap/backlog sync never happened

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/foundation/roadmap.md:49, :150 (S-04 detail); GitHub issue #9; Project board item PVTI_lAHOAg06b84BjEeyzg6VIsk
- **Detail**: Plan's Phase 4 explicitly required flipping S-04's roadmap status forward. Verified directly: `roadmap.md` still shows S-04 as `in-progress` in both the at-a-glance table and the detail section; `gh issue view 9` shows the issue still `OPEN` with zero comments; the Project board's `Roadmap Status` field is still `Ready` (not even bumped to `In Progress`). `git show c640e29` (the Phase 4 closeout commit) touched only `plan.md`'s Progress checkboxes, never `roadmap.md`. This also violates AGENTS.md's hard rule: "When starting or finishing work on a roadmap item, update both the issue (comment/close) and the Project's Roadmap Status field... don't let it drift."
- **Fix**: Flip `roadmap.md`'s S-04 status (table row + detail section) to `done`, close GitHub issue #9 with a closing comment referencing this change, and set the Project board's `Roadmap Status` field to `Done`.
- **Decision**: FIXED — roadmap.md status flipped to `done` (table + detail); issue #9 closed with comment; Project board Roadmap Status set to Done. Note: this required overriding AGENTS.md's "never modify context/" hard rule — user explicitly approved the override for this edit.

### F2 — `@Scheduled(fixedRateString=...)` relies on an implicit single-thread scheduler assumption

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: src/main/java/pl/tul/deltabrief/briefing/application/ScheduledBriefingRunner.java:51
- **Detail**: `fixedRateString` schedules the next tick `period` after the *previous tick's start*, not its completion. Non-overlapping ticks currently only hold because Spring Boot's autoconfigured `ThreadPoolTaskScheduler` defaults to pool size 1 — nothing in `SchedulingConfig` or `application.properties` states or enforces this. If that default is ever raised (e.g. someone adds an unrelated `@Scheduled` job and bumps `spring.task.scheduling.pool.size`), overlapping ticks could double-dispatch the same due topic within one window, since `nextDueAt` only advances per-topic on completion.
- **Fix**: Switch to `fixedDelayString` (measures the interval from the previous execution's *completion*, independent of thread-pool size), and add a short comment in `SchedulingConfig` documenting the single-thread-scheduler dependency this runner relies on.
- **Decision**: FIXED — switched `@Scheduled(fixedRateString=...)` to `fixedDelayString` in ScheduledBriefingRunner.java:51-54, with an inline comment explaining why. `ScheduledBriefingRunnerTests` re-run green.

### F3 — `updateSchedule` anchors the recompute at edit-time, not the last-generation anchor the plan specified

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/tul/deltabrief/topic/application/TopicService.java:115-121
- **Detail**: Plan said `updateSchedule` should recompute `nextDueAt` "from the topic's current last-generation anchor," keeping "last successful generation + interval" as the single rule for `next_due_at` everywhere (explicitly called out in the plan's Current-State-Analysis as a design goal). The shipped code instead anchors the recompute at `Instant.now()` at edit time, with an inline Javadoc justifying it as "editing the schedule is itself a reset." This is a genuine behavioral divergence — e.g. editing a WEEKLY topic's preferred hour does not preserve the remaining time in its current 7-day cycle; it restarts the interval from the moment of the edit.
- **Fix A ⭐ Recommended**: Keep the shipped now()-anchored reset behavior; it matches what a user editing a schedule would intuitively expect ("I changed it, the countdown restarts") and avoids surprising immediate-due-date jumps that anchoring to a stale last-generation timestamp could produce. Record this as a deliberate refinement in `plan.md`/`change.md` so future readers don't read it as unresolved drift.
  - Strength: Simpler, more predictable mental model for the edit action; sidesteps the edge case where recomputing from a stale anchor could make the topic immediately overdue.
  - Tradeoff: Diverges from the literally-approved plan without an explicit sign-off captured anywhere at implementation time.
  - Confidence: MEDIUM — reasonable UX argument, but this is a product-behavior call, not a code-correctness one.
  - Blind spot: Not confirmed with a product owner whether "preserve cadence from last generation" was load-bearing for any workflow.
- **Fix B**: Change `updateSchedule` to anchor the recompute at the topic's last-generation timestamp (falling back to `createdAt`), matching `createTopic`'s anchor logic and the plan's literal text.
  - Strength: Matches the approved plan exactly; keeps a single, consistent rule for what drives `next_due_at` everywhere.
  - Tradeoff: Can produce a `next_due_at` already in the past immediately after an edit (e.g. editing a DAILY topic to WEEKLY long after its last generation), firing on the very next poll tick — the plan didn't address this edge case.
  - Confidence: MEDIUM — matches plan text, but the edge case above is untested either way.
  - Blind spot: No test currently exercises what happens when a recomputed anchor is already overdue.
- **Decision**: FIXED via Fix A — documented as an intentional, confirmed plan deviation in change.md's ## Notes (no code change; current now()-anchored reset behavior kept).

### F4 — No test covers the edit endpoint's validation-failure path

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Success Criteria
- **Location**: src/test/java/pl/tul/deltabrief/topic/adapter/in/web/TopicControllerTests.java (and TopicFlowIntegrationTests.java)
- **Detail**: Phase 2's automated success criteria explicitly named "edit-endpoint tests pass ... covers the new edit endpoint's redirect/validation behavior." The redirect and cross-user-rejection paths are genuinely covered in `TopicFlowIntegrationTests`, but no test POSTs an invalid `preferredHour` (e.g. 24) to `/topics/{id}/edit` and asserts it re-renders `topic-edit` with binding errors — the validation-failure half of the named criterion is uncovered.
- **Fix**: Add a `TopicFlowIntegrationTests` case posting an out-of-range `preferredHour` to `/topics/{id}/edit` and asserting a re-render with `bindingResult` errors, mirroring the existing creation-form validation test pattern.
- **Decision**: FIXED — added `invalidPreferredHourOnEditShowsAnInlineErrorWithoutRedirecting` to TopicFlowIntegrationTests.java, mirroring `duplicateNameForTheSameUserShowsAnInlineErrorWithoutRedirecting`. Test passes.

### F5 — Post-closeout commit landed with no Progress entry

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: commit e10beba (after 4492b92, plan closeout epilogue)
- **Detail**: `e10beba` adds a legitimate, well-targeted coverage gap fix (topic-deletion cascade, and race-safety no-op tests for `recordSuccessfulGeneration`/`recordFailedScheduledGeneration` when a topic is deleted between the scheduler's due-query and dispatch) — verified genuine, not fabricated: the methods and race scenario it tests are real. But it landed after the plan's Phase 4 closeout with no corresponding `## Progress` entry, so `plan.md` now understates what actually shipped.
- **Fix**: Append a short addendum note to `plan.md`'s Progress section (or `change.md`) recording that `e10beba` closed a coverage gap discovered after Phase 4's closeout, so the on-disk plan history stays complete for future readers and `/10x-archive`.
- **Decision**: FIXED — addendum note appended to change.md's ## Notes.

### F6 — `TopicView` field shape diverges from the plan's naming

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/tul/deltabrief/topic/adapter/in/web/TopicController.java:183
- **Detail**: Plan specified `TopicView` gains `nextDueAt` (ISO string) + `nextDueAtFallback`. Shipped `TopicView` instead has `nextDueAt` (a human-formatted display string with "Manual" baked in as its own fallback) and `nextDueAtIso` (the raw ISO value) — same two pieces of information, reversed naming/roles. Capability matches; naming doesn't.
- **Fix**: No functional change needed; optionally rename fields to match the plan's literal names for future-reader clarity.
- **Decision**: SKIPPED — cosmetic only, not worth a rename.

### F7 — Scheduling properties live outside `SchedulingConfig`

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/tul/deltabrief/config/SchedulingConfig.java; src/main/java/pl/tul/deltabrief/briefing/application/ScheduledBriefingRunner.java
- **Detail**: Plan implied `app.scheduling.poll-interval` / `app.scheduling.max-concurrent-generations` would be declared alongside `SchedulingConfig`. Shipped `SchedulingConfig` is only `@Configuration @EnableScheduling`; the two properties are read via `@Value` directly in `ScheduledBriefingRunner`, and the poll-interval property is named `poll-interval-ms` (unit-suffixed) rather than `poll-interval`. Functionally correct — confirmed `max-concurrent-generations=3` stays under Hikari's `maximum-pool-size=5` as required.
- **Fix**: No functional issue; optionally move the `@Value` declarations into `SchedulingConfig` as named constants for discoverability.
- **Decision**: SKIPPED — no functional issue, not worth restructuring.

### F8 — `ScheduledBriefingRunner`'s executor lifecycle doesn't mirror `AsyncConfig`'s pattern

- **Severity**: 👁️ OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/main/java/pl/tul/deltabrief/briefing/application/ScheduledBriefingRunner.java
- **Detail**: `AsyncConfig` (the codebase's precedent for backgrounded-work configuration) declares its executor as a `@Bean` with explicit pool sizing and graceful shutdown (`setWaitForTasksToCompleteOnShutdown(true)`, `setAwaitTerminationSeconds(10)`). `ScheduledBriefingRunner` instead creates a fresh `Executors.newFixedThreadPool(maxConcurrentGenerations)` per tick with no graceful-shutdown handling on app stop (e.g. a Render redeploy mid-tick) — in-flight generations only get `ExecutorService.close()`'s best-effort `shutdownNow()`. Not a functional bug: any interrupted generation is silently retried on the next tick since `nextDueAt` stays unchanged on failure.
- **Fix**: No action required — the retry-next-tick behavior makes this harmless; optionally align with `AsyncConfig`'s explicit-bean + graceful-shutdown style for consistency if that was the intended precedent to mirror.
- **Decision**: SKIPPED — harmless, not worth the restructuring.
