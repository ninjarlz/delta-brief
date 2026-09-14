# Scheduled Briefing Generation Implementation Plan

## Overview

Add automatic, unattended briefing generation on a per-topic cadence (FR-008/FR-009). Each topic gets a `frequency` (manual / twice-daily / daily / every-other-day / weekly) and an optional preferred hour-of-day; an in-process Spring `@Scheduled` poll job checks every 15 minutes for topics whose next generation is due and runs them through the existing `BriefingService.generateBriefing`, with the schedule kept in sync whether a briefing was produced by the scheduler, a manual "Generate now" click, or the onboarding flow.

## Current State Analysis

- `Topic` (`pl.tul.deltabrief.topic.domain.Topic`) has no scheduling fields at all — just `id`, `userId`, `name`, `categoryId`, `description`, `createdAt`.
- `TopicService` has `createTopic`, `listTopics`, `deleteTopic` — no update/edit use case exists.
- `TopicController` has no edit endpoint; `TopicView` (its display record) carries only `id`, `name`, `categoryName`.
- The `topics` table (`V7__create_topics_table.sql`, `V10__add_topic_description_column.sql`) has no frequency/due-date columns. Latest migration on disk is `V12__equalize_curated_sources_per_category.sql` — the next one is `V13`.
- `briefings` table (`V8__create_briefings_table.sql`) has `topic_id`, `generated_at` (indexed together, newest-first) — this is what "last generated_at" for the due-date calculation reads from.
- No `@Scheduled` or `@EnableScheduling` exists anywhere in the codebase. The only scheduling-adjacent precedent is `pl.tul.deltabrief.config.AsyncConfig` (`@EnableAsync`, one `@ConditionalOnProperty`-gated executor bean for async email sending) — this is the pattern a new scheduling config class should follow.
- `BriefingService.generateBriefing(TopicId, UserId)` (`pl.tul.deltabrief.briefing.application.BriefingService`) already takes plain ID types and is directly callable from a non-HTTP context with no changes to its core logic.
- No `Clock` abstraction exists anywhere in the codebase — all time-dependent code calls `Instant.now()` directly. This plan does not introduce one (see Testing Strategy).
- `BriefingControllerTests` establishes the project's pattern for testing pure logic: package-private static helper methods are unit-tested directly with no Spring context or mocking framework.
- Hikari pool is capped at `maximum-pool-size=5` (`application.properties`) — a real constraint on how much scheduled-generation concurrency is safe.

### Key Discoveries:

- `BriefingService.generateBriefing` throws `TopicNotFoundException` and lets `BriefingContentGenerator.GenerationFailedException` propagate uncaught on LLM failure — the scheduler's dispatch loop must catch this per-topic so one failing topic doesn't stop the batch (mirrors the existing per-source failure tolerance in `ingestSources`).
- The topic creation flow (`TopicController.createTopic`) does not itself trigger the onboarding briefing generation — so a topic's `next_due_at` at creation time is a placeholder that gets overwritten by the same post-generation "advance the schedule" step used for every other generation path (manual, onboarding, scheduled). This keeps "what sets `next_due_at`" to one rule everywhere: *last successful generation's `generated_at` + frequency interval, nudged to the preferred hour*.
- The app has no per-user timezone concept anywhere (frontend localizes briefing timestamps client-side via JS, but that's display-only). Preferred-hour nudging is computed in UTC — see Open Risks in the brief.

## Desired End State

A topic can be created (or later edited) with a frequency and an optional preferred hour. Every 15 minutes, a background job generates fresh briefings for all due topics, advances their schedule on success, and records a failure marker on failure (which the topic page surfaces) without giving up — the same topic is retried on the next 15-minute tick, indefinitely. A manual "Generate now" click and the onboarding briefing both participate in the same schedule-advancing rule as scheduled runs. The topic list/detail pages show the next expected run and the outcome of the last scheduled attempt.

**Verification**: `./gradlew test` passes; manually create a topic with a short-interval-like verification (seed a past-due `next_due_at` via `psql`, wait for/trigger a poll tick, confirm a new briefing appears and `next_due_at` advances); manually edit a topic's frequency and confirm the displayed "next briefing" updates.

## What We're NOT Doing

- No per-user timezone setting — preferred hour is UTC-based for v1.
- No email notification on scheduled-generation failure (blocked on FR-012/S-06, the email-delivery slice, which doesn't exist yet — noted in `change.md` as a follow-up idea, not built here).
- No auto-pause of a topic after repeated scheduled failures — it retries every poll cycle indefinitely.
- No global daily cap on total scheduled generations across all users.
- No change to how a failing *source* (RSS fetch) is handled — that tolerance already exists in `BriefingService.ingestSources` and is untouched.
- No `Clock`/time-provider abstraction — kept consistent with the rest of the codebase's direct `Instant.now()` usage.

## Implementation Approach

Layer the feature bottom-up: schema + pure due-date math first (fully unit-testable in isolation), then the user-facing frequency controls and visibility, then the scheduler itself which ties both together and updates the manual-generation path to use the same schedule-advance rule.

## Phase 1: Data model & pure scheduling logic

### Overview

Add the schema and domain fields for frequency/schedule state, and implement the due-date calculation as a pure, directly-testable function with no Spring dependencies.

### Changes Required:

#### 1. Schema migration

**File**: `src/main/resources/db/migration/V13__add_topic_scheduling_columns.sql`

**Intent**: Add the columns needed to track each topic's cadence and last scheduled outcome, and backfill every existing topic to `DAILY` (per explicit product decision) with a computed initial `next_due_at`.

**Contract**: Adds to `topics`: `frequency VARCHAR(20) NOT NULL DEFAULT 'DAILY'`, `preferred_hour SMALLINT NULL` (0-23), `next_due_at TIMESTAMPTZ NULL` (null only for `MANUAL` topics), `last_scheduled_attempt_at TIMESTAMPTZ NULL`, `last_scheduled_status VARCHAR(20) NULL` (`SUCCESS`/`FAILURE`). Backfill: for every existing row, set `next_due_at` = (that topic's `MAX(briefings.generated_at)`, falling back to `topics.created_at` if it has no briefings yet) + 1 day.

#### 2. `Topic` domain

**File**: `src/main/java/pl/tul/deltabrief/topic/domain/Topic.java`

**Intent**: Represent the new scheduling state on the aggregate, plus a `Frequency` enum and `ScheduledRunStatus` enum living alongside it.

**Contract**: New fields `Frequency frequency`, `Integer preferredHour` (nullable), `Instant nextDueAt` (nullable), `Instant lastScheduledAttemptAt` (nullable), `ScheduledRunStatus lastScheduledStatus` (nullable) — following the existing fluent-accessor style. `Frequency` enum: `MANUAL, TWICE_DAILY, DAILY, EVERY_OTHER_DAY, WEEKLY`, each carrying its `Duration` interval (`MANUAL` has none). `ScheduledRunStatus`: `SUCCESS, FAILURE`. Add a mutator (e.g. `applySchedule(Frequency, Integer preferredHour, Instant nextDueAt)`) for use by both the creation and edit flows, and a mutator for recording a scheduled attempt's outcome (advance-on-success / mark-failure-on-failure), used by Phase 3.

#### 3. `ScheduleCalculator` (new pure utility)

**File**: `src/main/java/pl/tul/deltabrief/topic/domain/ScheduleCalculator.java` (or `application` — colocate with `Frequency`; implementer's call based on final package shape)

**Intent**: Compute the next due timestamp from a frequency, an optional preferred hour, and the anchor time (the last successful generation's `generatedAt`, or `createdAt` when there is none yet).

**Contract**: `static Instant nextDueAt(Instant anchor, Frequency frequency, Integer preferredHour)`. Returns `null` when `frequency == MANUAL`. Otherwise: `base = anchor.plus(frequency.interval())`; if `preferredHour != null`, round `base` **forward** (never backward/earlier) to the next UTC instant whose hour-of-day equals `preferredHour` — if `base`'s own hour already equals `preferredHour`, `base` is returned unchanged (it already lands on the preferred hour, don't push it a further day out).

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "pl.tul.deltabrief.topic.domain.ScheduleCalculatorTests"` passes, covering: all 5 frequencies with no preferred hour, a preferred hour ahead of the base's hour same-day, a preferred hour already passed (rolls to next day), a preferred hour exactly equal to the base's hour (no extra rollover), and `MANUAL` returning `null`.
- `./gradlew build` succeeds (migration applies cleanly against the Testcontainers Postgres instance used by the existing integration tests).

#### Manual Verification:

- N/A — this phase has no user-visible surface yet.

---

## Phase 2: Topic creation/edit UI + schedule visibility

### Overview

Let users set and change a topic's frequency/preferred hour, and see the topic's next expected run and last scheduled outcome.

### Changes Required:

#### 1. Creation form

**File**: `src/main/java/pl/tul/deltabrief/topic/application/dto/CreateTopicRequest.java`, `src/main/resources/templates/topic-form.html`

**Intent**: Add a required `frequency` selector (defaulting to `DAILY`, matching the existing-topics backfill default) and an optional preferred-hour input to the topic creation form.

**Contract**: `CreateTopicRequest` gains `Frequency frequency` (`@NotNull`, defaulted to `DAILY` when the form is freshly rendered) and `Integer preferredHour` (`@Min(0) @Max(23)`, optional). `topic-form.html` gets a frequency `<select>` and an hour input, following the existing field-plus-error-message pattern already used for `name`/`categoryId`/`description`.

#### 2. `TopicService` creation + new update use case

**File**: `src/main/java/pl/tul/deltabrief/topic/application/TopicService.java`

**Intent**: `createTopic` sets the topic's initial frequency/preferred hour and a placeholder `nextDueAt` (anchored at `createdAt`, overwritten on first real generation per the Phase 3 hook). Add `updateSchedule(UserId, TopicId, Frequency, Integer preferredHour)` for the edit flow, recomputing `nextDueAt` from the topic's current last-generation anchor (read via a new `TopicRepository`/`BriefingRepository` lookup — implementer's call which port owns "last generated_at for a topic", follow whichever existing cross-module query pattern `TopicSummary` already established).

**Contract**: `createTopic`'s signature gains the two new parameters (update all call sites: `TopicController.createTopic`). New method `updateSchedule` on `TopicService`, using the same ownership-check convention as `deleteTopic` (silently no-ops or throws `TopicNotFoundException`-equivalent for a topic ID the caller doesn't own — match whatever `deleteTopic`/`findSummaryByIdAndUserId` already do for consistency).

#### 3. Edit endpoint + topic list/detail visibility

**File**: `src/main/java/pl/tul/deltabrief/topic/adapter/in/web/TopicController.java`, `src/main/resources/templates/topics.html`, `src/main/resources/templates/topic-form.html` (or a new edit template if the form differs enough)

**Intent**: `GET /topics/{id}/edit` + `POST /topics/{id}/edit` to change frequency/preferred hour. `TopicView` and the topics list template show each topic's next expected run (formatted via the same localized-timestamp pattern already built for briefing dates — server-rendered UTC fallback + client-side `app.js` enhancement) and, when `lastScheduledStatus == FAILURE`, a visible failure indicator.

**Contract**: `TopicView` record gains `String nextDueAt` (ISO string for the JS enhancer) + `String nextDueAtFallback` + `boolean lastRunFailed`. New routes follow the existing `@PathVariable Long id` + ownership-scoped service call convention already used by `deleteTopic`.

### Success Criteria:

#### Automated Verification:

- `./gradlew test` passes, including new tests for `TopicService.updateSchedule` (happy path, wrong-owner rejection) following the existing `TopicServiceTests` structure.
- `./gradlew test --tests "*TopicControllerTests*"` (or equivalent) covers the new edit endpoint's redirect/validation behavior.

#### Manual Verification:

- Create a topic, confirm the frequency/hour fields render and persist.
- Edit an existing topic's frequency, confirm the topic list's "next briefing" value updates accordingly.
- Confirm the failure indicator is visually present (can be forced by manually setting `last_scheduled_status = 'FAILURE'` via `psql` for a quick check) and disappears after a successful run.

**Implementation Note**: Pause here for manual confirmation before proceeding to Phase 3.

---

## Phase 3: The scheduler + manual-trigger schedule integration

### Overview

Wire the actual `@Scheduled` poll job, and make every generation path (scheduled, manual, onboarding) advance the same `next_due_at`/`last_scheduled_status` state through one shared step.

### Changes Required:

#### 1. Scheduling configuration

**File**: `src/main/java/pl/tul/deltabrief/config/SchedulingConfig.java`

**Intent**: Enable Spring scheduling, following `AsyncConfig`'s single-purpose `@Configuration` class style.

**Contract**: `@Configuration @EnableScheduling`. New properties in `application.properties`: `app.scheduling.poll-interval` (default `15m`, using Spring's duration syntax like the rest of the `app.*` namespace) and `app.scheduling.max-concurrent-generations` (default e.g. `3` — bounded below the Hikari pool cap of 5 to leave headroom for concurrent user requests).

#### 2. Poll job + bounded dispatch

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/ScheduledBriefingRunner.java` (new, in `briefing.application` alongside `BriefingService` — the module that already owns `generateBriefing`)

**Intent**: Every poll tick, find topics with `frequency != MANUAL AND next_due_at <= now`, and generate a briefing for each via a small bounded worker pool, so a large due-batch doesn't run unbounded or block the next tick indefinitely.

**Contract**: `@Scheduled(fixedRateString = "${app.scheduling.poll-interval}")` method. Dispatch uses a bounded pool sized from `app.scheduling.max-concurrent-generations` (same virtual-thread-per-task style already used in `BriefingService.ingestSources`, bounded via a fixed-size `Semaphore` or `Executors.newFixedThreadPool`). Per-topic: catch any exception from `generateBriefing`, call the shared schedule-advance step's failure branch on catch, success branch otherwise — never let one topic's failure stop the batch.

#### 3. Shared schedule-advance step

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java` (or a small collaborator it and `ScheduledBriefingRunner` both call)

**Intent**: One rule, used by every successful/failed generation attempt regardless of trigger (manual `BriefingController` "Generate now", onboarding, or the scheduler): on success, recompute and persist `next_due_at` via `ScheduleCalculator.nextDueAt(generatedAt, frequency, preferredHour)` and set `last_scheduled_status = SUCCESS`; on failure (scheduler path only — manual failures already surface synchronously as an HTTP error to the user), set `last_scheduled_attempt_at = now` and `last_scheduled_status = FAILURE`, leaving `next_due_at` unchanged so the next 15-minute poll retries it.

**Contract**: `BriefingService.generateBriefing` gains this schedule-advance call on its success path (so manual and onboarding generations reset the schedule per the confirmed "manual generation resets the schedule" decision); `ScheduledBriefingRunner` calls the failure-branch update itself when it catches an exception (since `generateBriefing` throws before returning, it can't update state on the failure path itself for that call).

### Success Criteria:

#### Automated Verification:

- `./gradlew test` passes, including a new WireMock-stubbed integration test (styled after `BriefingFlowIntegrationTests`) that seeds a topic with a past-due `next_due_at`, invokes `ScheduledBriefingRunner`'s poll method directly (no real 15-minute wait, no `Clock` abstraction — just call the method), and asserts a new briefing was created and `next_due_at` advanced.
- A second such test asserts that a simulated generation failure (WireMock stubbed to return an error) leaves `next_due_at` unchanged and sets `last_scheduled_status = FAILURE`.
- A test on the existing manual "Generate now" flow (`BriefingFlowIntegrationTests` or a new test) asserts `next_due_at` advances after a manual trigger too.

#### Manual Verification:

- Seed a real topic's `next_due_at` into the past via `psql`, wait up to one poll interval (or temporarily lower `app.scheduling.poll-interval` for the check), confirm a new briefing appears and the topic page's "next briefing" advances.
- Confirm a deliberately-broken topic (e.g. temporarily point its category at no sources, or force an LLM error) shows the failure indicator from Phase 2 and is retried on the following tick without needing intervention.

**Implementation Note**: Pause here for manual confirmation before proceeding to Phase 4.

---

## Phase 4: Documentation & roadmap closeout

### Overview

Close the loop on the process artifacts once the feature is verified working.

### Changes Required:

#### 1. Roadmap & change notes

**File**: `context/foundation/roadmap.md`, `context/changes/scheduled-briefing-generation/change.md`

**Intent**: Flip S-04's status forward per the standard `/10x-implement`/`/10x-archive` roadmap-sync convention (handled by those skills, not manually here — this phase just confirms it happened). Record the deferred "email the user on scheduled-generation failure, escalate to admin on repeated failure" idea in `change.md`'s `## Notes` so it isn't lost, tagged as blocked on FR-012/S-06.

**Contract**: A `## Notes` addition to `change.md`, no code changes.

### Success Criteria:

#### Automated Verification:

- N/A (documentation-only phase).

#### Manual Verification:

- `context/changes/scheduled-briefing-generation/change.md` contains the deferred-idea note.

---

## Testing Strategy

### Unit Tests:

- `ScheduleCalculator` — all 5 frequencies, preferred-hour nudging edge cases (already-passed hour, exact-match hour, no hour set), `MANUAL` → `null`.
- `TopicService.updateSchedule` — happy path, wrong-owner rejection.

### Integration Tests:

- Scheduled poll job: due topic gets generated, `next_due_at` advances; failing topic leaves `next_due_at` unchanged and marks `FAILURE`.
- Manual "Generate now" also advances `next_due_at` (schedule-reset behavior).

### Manual Testing Steps:

1. Create a topic with `DAILY` frequency and a preferred hour; confirm the "next briefing" shown matches the expected nudged time.
2. Seed a past-due `next_due_at` via `psql`, trigger/await a poll tick, confirm a new briefing and an advanced `next_due_at`.
3. Force a generation failure for one topic, confirm the in-app failure indicator appears and the topic is retried (not skipped or paused) on the next tick.
4. Edit an existing topic's frequency and confirm the schedule recomputes.

## Performance Considerations

Dispatch concurrency is explicitly bounded (`app.scheduling.max-concurrent-generations`, default 3) to stay under the 5-connection Hikari pool cap, since each concurrent generation also holds a connection for its own briefing/topic reads and writes.

## Migration Notes

`V13` backfills every existing topic to `DAILY` with a computed `next_due_at` — this is a deliberate, explicit product decision (not a conservative default) to enable automatic generation broadly rather than opt-in.

## References

- Prior related work: `context/archive/2026-09-13-first-onboarding-and-delta-briefing/plan.md` (established the citation-renumbering and source-diversity patterns `BriefingService`/`BriefingController` now use).

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Data model & pure scheduling logic

#### Automated

- [x] 1.1 `ScheduleCalculatorTests` passes covering all frequencies and hour-nudge edge cases — 876205f
- [x] 1.2 `./gradlew build` succeeds (migration applies cleanly) — 876205f

### Phase 2: Topic creation/edit UI + schedule visibility

#### Automated

- [x] 2.1 `./gradlew test` passes including new `TopicService.updateSchedule` tests — 36b0e87
- [x] 2.2 New edit-endpoint tests pass — 36b0e87

#### Manual

- [x] 2.3 Create a topic, confirm frequency/hour fields render and persist — 36b0e87
- [x] 2.4 Edit an existing topic's frequency, confirm "next briefing" updates — 36b0e87
- [x] 2.5 Confirm failure indicator appears/disappears correctly — 36b0e87

### Phase 3: The scheduler + manual-trigger schedule integration

#### Automated

- [x] 3.1 Integration test: due topic generates and `next_due_at` advances
- [x] 3.2 Integration test: failed generation leaves `next_due_at` unchanged, marks `FAILURE`
- [x] 3.3 Test: manual "Generate now" also advances `next_due_at`

#### Manual

- [x] 3.4 Real poll-tick verification via seeded past-due `next_due_at`
- [x] 3.5 Deliberately-broken topic shows failure indicator and retries next tick

### Phase 4: Documentation & roadmap closeout

#### Manual

- [ ] 4.1 `change.md` contains the deferred email-notification note
