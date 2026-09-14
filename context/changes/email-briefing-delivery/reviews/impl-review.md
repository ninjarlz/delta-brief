<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Email Briefing Delivery

- **Plan**: context/changes/email-briefing-delivery/plan.md
- **Scope**: Phase 1 of 2 (full plan — both phases complete)
- **Date**: 2026-09-14
- **Verdict**: APPROVED
- **Findings**: 0 critical, 1 warning, 0 observations

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | PASS |
| Scope Discipline | WARNING |
| Safety & Quality | PASS |
| Architecture | PASS |
| Pattern Consistency | PASS |
| Success Criteria | PASS |

## Findings

### F1 — Two unrelated files bundled into the Phase 1 commit

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: README.md; context/foundation/roadmap.md
- **Detail**: The Phase 1 commit (`cf79bc3`) bundled an unrelated README.md wording change (moving the "email delivery is core" callout into flowing prose) and the roadmap's S-06 status flip (`ready` → `in-progress`), both pre-existing dirty files at commit time. You explicitly chose "Stage all" when prompted during the commit ritual's dirty-path check; both diffs confirmed cosmetic/process-only, not feature-related scope creep. Same pattern as the prior `browse-briefing-history` review's F1.
- **Fix**: No action needed — deliberate, consented, and documented in the commit message.
- **Decision**: SKIPPED — as expected; deliberate, consented decision at commit time.

## Automated Verification (re-run at review time)

- `./gradlew test` (full suite, fresh `--rerun-tasks`): PASS
- `./gradlew build` (fresh, migration V14 applies cleanly): PASS
- Targeted classes (`TopicTests`, `TopicServiceTests`, `TopicRepositoryAdapterTests`, `CitationRendererTests`, `BriefingControllerTests`, `UserRepositoryAdapterTests`, `BriefingServiceTests`, `ScheduledBriefingRunnerTests`): PASS

## Notes

- Both sub-agent reviews reported full MATCH on all 11 planned changes across both phases, with real assertions (not filename-only coverage) for every named success criterion.
- The two highest-risk correctness requirements in this feature were both verified directly in source:
  - **`@Async` self-invocation**: `BriefingEmailNotifier` is a genuinely separate Spring bean, constructor-injected into `BriefingService` and called through the proxy — not a method `BriefingService` calls on itself, which would have silently made the "async" send run synchronously.
  - **Module boundary**: `UserRepository.findEmailById` returns `Optional<String>`, never `Optional<User>` — `UserId`'s own "never import User itself" rule is respected, mirroring how `TopicSummary` already avoided exposing the full `Topic` aggregate.
- `generateBriefing`'s signature and both its callers (`BriefingController.generate`, `ScheduledBriefingRunner.generateOne`) are confirmed unchanged — the "one shared hook point, zero changes to either trigger" design goal holds.
- Citation rendering is provably consistent between the web view and the email: both now call the same extracted `CitationRenderer.render(briefing)`, and `BriefingControllerTests`' diff is Javadoc-only — no assertion changes, confirming the extraction didn't alter observable behavior.
- Failure isolation confirmed on both paths: an email-send failure and a missing recipient email are both logged and swallowed, never propagating back to fail an otherwise-successful generation.
- Two purely cosmetic, non-actionable notes surfaced (not formalized as findings): `TopicNameAndCategoryView.getEmailEnabled()` uses `getX` rather than Spring Data's more common `isX` boolean-projection naming (both work correctly); `AsyncConfig`'s updated comment could be tightened now that it names two async consumers. Neither affects correctness.
