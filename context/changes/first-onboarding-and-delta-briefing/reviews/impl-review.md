<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: First Onboarding and Delta Briefing

- **Plan**: context/changes/first-onboarding-and-delta-briefing/plan.md
- **Scope**: Full plan — Phases 1-3 already individually reviewed (see `reviews/impl-review-phase-1.md`, `-phase-2.md`, `-phase-3.md`, all resolved); this pass covers **Phase 4 (Orchestration & Web) + the mid-flight Addendum (optional topic description, FR-004)**, neither of which had been reviewed before. Both are currently uncommitted.
- **Date**: 2026-09-14
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 2 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | PASS |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS (automated 4.1-4.4 + 4.8 all pass, 76 tests; addendum manual items 4.9/4.10 still pending your confirmation — acknowledged, not a failure) |

## Findings

### F1 — TestcontainersDatasourceConfig's container-reuse check has a TOCTOU race window

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java:86-137
- **Detail**: The Phase 4 fix (reuse an already-running local Postgres container instead of always force-removing and restarting it) does check-then-act: `isLocalContainerRunning()` then, if false, `removeStaleLocalContainer()` + create+start on a fixed name/port. If two Spring contexts needing this bean bootstrap concurrently (parallel test execution), both could observe "not running" and both proceed to remove+recreate — a Docker name conflict, or one thread tearing down what the other just started (the exact corruption this fix exists to prevent). Confirmed safe **today**: neither `build.gradle` nor any `junit-platform.properties` configures parallel test execution (Gradle defaults to sequential within one test JVM), so this can't actually happen right now — but that safety rests on an unstated assumption that could silently break if test parallelism is ever turned on later.
- **Fix A ⭐ Recommended**: Add an explicit code comment on `localDataSource()` stating the sequential-test-execution assumption this reuse logic depends on, so a future change enabling Gradle/JUnit5 parallelism is forced to notice and address this.
  - Strength: Zero runtime cost, makes the latent assumption visible exactly where a future editor would need to see it.
  - Tradeoff: Doesn't actually prevent the race if parallelism is enabled without reading the comment.
  - Confidence: HIGH — matches this codebase's existing style of documenting non-obvious constraints inline (e.g. `TestcontainersDatasourceConfig`'s own existing Javadoc already explains the fixed-port/host-network tradeoffs this way).
  - Blind spot: None significant — this is test-only infrastructure, never shipped to production.
- **Fix B**: Guard the whole check-and-recreate sequence with a static `synchronized` block / lock object.
  - Strength: Actually closes the race, regardless of future test-parallelism config changes.
  - Tradeoff: Adds real synchronization complexity to fix a race that can't currently occur — solving a problem this project doesn't have yet.
  - Confidence: MED — correct in principle, but unverified whether Testcontainers' own container-start calls are already safe to call concurrently from within a lock (could serialize unnecessarily, not incorrectly).
  - Blind spot: Haven't verified how Gradle would behave if parallel test execution were turned on class-by-class vs. method-by-method — the exact serialization boundary a lock here would need to match.
- **Decision**: SKIPPED — not worth fixing now.

### F2 — `BriefingController.latest` re-runs ownership checks and re-fetches the same summary list

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality (Performance)
- **Location**: src/main/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingController.java:53-88
- **Detail**: `latest()` calls `listSummaries(...)` to find the newest briefing ID, then delegates to `showBriefing()`, which independently calls `findOne(...)` (re-running the ownership check) **and** `listSummaries(...)` again (re-running the ownership check and re-fetching the identical list `latest()` already had). Net effect per `/latest` request: 3 ownership-check queries plus 2 identical summary-list queries. Not a correctness bug — every query is cheap and correctly scoped — just redundant.
- **Fix**: Thread the already-fetched `summaries` list into `showBriefing` instead of re-deriving it from a fresh `topicId`/`userId` pair.
- **Decision**: FIXED — `showBriefing` now takes `history` as a parameter; `latest()` passes its already-fetched list, `show()` fetches it once itself. Verified via full test suite re-run, still green.

### F3 — `CategoryRepository` dependency in `BriefingService` wasn't itemized in the plan's file list

- **Severity**: OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java:39, 62-64
- **Detail**: `BriefingService` injects `topic.application.port.out.CategoryRepository` (to resolve `categoryName` for the prompt) — necessary and correctly scoped, but Phase 4's plan text didn't explicitly call this out the way the `findSummaryByIdAndUserId` broadening was disclosed. Minor documentation gap, not a defect.
- **Fix**: None needed — the dependency is sound; noting for the record only.
- **Decision**: ACCEPTED — confirmed sound, no code change.
