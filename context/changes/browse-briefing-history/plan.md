# Browse Briefing History Implementation Plan

## Overview

Add a dedicated, directly-linkable history page per topic (FR-011) — reusing the summary
projection and ownership-scoped service layer that S-03 already built — and wire an entry
point into it from the topics list.

## Current State Analysis

- `Briefing` (`pl.tul.deltabrief.briefing.domain.Briefing`) has no `userId` — ownership is
  always resolved transitively through the owning `Topic`.
- `BriefingRepository.findSummariesByTopicId(TopicId)` (`briefing/application/port/out/BriefingRepository.java`)
  already returns every briefing for a topic as a lightweight `BriefingSummary(BriefingId, BriefingType, Instant generatedAt)`
  projection, newest-first, backed by an index on `(topic_id, generated_at DESC)` — no new query needed.
- `BriefingService.listSummaries(TopicId, UserId)` (`briefing/application/BriefingService.java:182-187`)
  already wraps that query with an ownership check via `topicRepository.findSummaryByIdAndUserId` —
  but it collapses two different cases into the same empty list: "topic not owned/doesn't exist"
  and "topic owned, zero briefings yet." A new page needs to tell these apart (see Key Discoveries).
- `BriefingController` (`briefing/adapter/in/web/BriefingController.java`) already has
  `GET /topics/{topicId}/briefings/latest` and `GET /topics/{topicId}/briefings/{briefingId}`,
  both rendering `briefing.html`, which already includes an inline "History" sidebar
  (`briefing.html:63-80`) built from `toHistoryEntryView` (`BriefingController.java:210-218`) —
  title formatted as `"{topicName} #{ordinal}"` (with `" (onboarding briefing)"` appended for
  the first entry), plus formatted + ISO timestamp and a `current` flag.
- There is no route that lists a topic's briefings without also loading one briefing's full
  content (no bare `GET /topics/{topicId}/briefings`), and `topics.html` has no link into any
  briefing view that doesn't also trigger a new generation (only a `POST` "Generate briefing"
  button, `topics.html:39-41`).
- The archived S-03 plan (`context/archive/2026-09-13-first-onboarding-and-delta-briefing/plan.md:38`)
  explicitly deferred this: *"A full browsable briefing history page (S-05) — this slice ships
  only a minimal inline list of past briefings alongside the latest one; S-05 owns the real
  history experience."*
- No pagination exists anywhere in the codebase (confirmed via grep for `Pageable`/`Page<`) —
  the one existing precedent for avoiding it is a flat 20-item cap on the topics list
  (`TopicService.MAX_TOPICS_PER_USER`), not used for briefings.

### Key Discoveries:

- `BriefingService.listSummaries` returning `List.of()` for both "not owned" and "owned, empty"
  means it cannot be reused as-is for the new page: reusing it directly would either leak that
  a non-owned topic ID exists (by rendering an empty state instead of redirecting) or, if
  redirect-on-empty is kept, would incorrectly redirect an *owned* topic with zero briefings
  away from its own (valid) empty state. The new page needs a service method that separates
  these two cases explicitly, mirroring how `findOne` (`BriefingService.java:198-205`) already
  returns `Optional.empty()` only for the not-owned case.
- `toHistoryEntryView(BriefingSummary, BriefingId currentBriefingId, String topicName, int ordinal)`
  (`BriefingController.java:210-218`) can be reused unmodified for the new page's rows: its
  `current` flag is computed as `summary.id().equals(currentBriefingId)`, so passing `null` as
  `currentBriefingId` safely yields `current = false` for every row (`Object.equals(null)` is
  always `false`, no null-dereference risk since the null is the argument, not the receiver).
- The existing ownership-leak-avoidance convention (redirect to `/`, never a distinguishable
  404) is documented at `BriefingController.java:88-99` and must extend to the new route.

## Desired End State

Any topic's briefing history is reachable directly, independent of generating or viewing a
specific briefing: a "View briefings" link on each topic card (`topics.html`) leads to a
dedicated page listing every briefing for that topic (title, type, timestamp), newest first,
each linking into the existing single-briefing detail view. A topic with zero briefings yet
shows a friendly empty state with a way to generate the first one, instead of a dead end or a
redirect. The existing inline history sidebar on `briefing.html` is untouched.

**Verification**: `./gradlew test` passes; manually click "View briefings" from the topics
list for a topic with several briefings and confirm the list and its links work; do the same
for a freshly created topic with zero briefings and confirm the empty state appears.

## What We're NOT Doing

- No pagination or size cap on the history list — shown unbounded for v1, matching the PRD's
  `data_volume: small` framing; revisit only if this becomes a real problem later.
- No content preview (e.g. a snippet of "significance") per row — rows show title, type, and
  timestamp only, reusing the existing cheap `BriefingSummary` projection with no risk of
  loading full briefing content just to render a list.
- No changes to `briefing.html`'s existing inline history sidebar — it stays exactly as
  shipped in S-03; the new page is a separate, fuller entry point.
- No "hasBriefings" flag threaded into `TopicView`/`TopicController` — the "View briefings"
  link is always shown on every topic card; the history page itself handles the empty case.
- No change to how a briefing is generated, classified, or rendered — this plan only adds a
  way to reach existing briefings, not any new generation or content logic.

## Implementation Approach

Add one new service method that cleanly distinguishes "topic not owned" from "topic owned,
no briefings yet," one new controller route + template built on top of it, then wire the
topics-list entry point in a second phase so the page can be manually verified in isolation
first.

## Phase 1: History page (backend + route + template)

### Overview

Add the backend method, route, and template for a directly-linkable, per-topic history page.

### Changes Required:

#### 1. `BriefingService` history method

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java`

**Intent**: Provide a single ownership-checked lookup that returns the topic's name alongside
its full briefing-summary list, so the controller can tell "not owned" (redirect) apart from
"owned, zero briefings" (show empty state) — something `listSummaries` alone can't do.

**Contract**: New method `Optional<TopicHistory> getHistory(TopicId topicId, UserId userId)`
and a small new record `TopicHistory(String topicName, List<BriefingSummary> summaries)`
(nested in `BriefingService`, mirroring how `BriefingDetail` already pairs a briefing with its
topic name). Returns `Optional.empty()` when `topicRepository.findSummaryByIdAndUserId` finds
nothing; otherwise `Optional.of(new TopicHistory(topic.name(), briefingRepository.findSummariesByTopicId(topicId)))`
— `summaries` may legitimately be an empty list.

#### 2. New history route

**File**: `src/main/java/pl/tul/deltabrief/briefing/adapter/in/web/BriefingController.java`

**Intent**: Expose the new lookup as a page reachable independent of generating or viewing a
specific briefing.

**Contract**: `@GetMapping("/topics/{topicId}/briefings")` (does not collide with the existing
`@PostMapping` on the same path, or with the more specific `/latest` and `/{briefingId}`
routes). On `Optional.empty()` from `getHistory`, `return "redirect:/"` — same
ownership-leak-avoidance convention as every other route in this controller. Otherwise builds
`List<HistoryEntryView>` from the returned summaries the same way `showBriefing` already does
(`IntStream.range` + `toHistoryEntryView`, passing `null` for `currentBriefingId` so `current`
is always `false`), adds `topicId`, `topicName`, and `history` to the model, and renders a new
`briefing-history` template.

#### 3. New template

**File**: `src/main/resources/templates/briefing-history.html`

**Intent**: Render the topic's full briefing list as its own page, or a friendly empty state
if none exist yet.

**Contract**: No new UI vocabulary — this page is assembled entirely from markup, classes, and
conventions that already exist elsewhere in the app, not new styling. Copy `briefing.html`'s
page shell verbatim: same `<head>` (`pico.min.css`, `app.css`, `app.js` deferred), same
`<nav th:replace="~{fragments/navbar :: navbar}">`, same `<main class="app-content">`, same
`<a ... class="back-link">← Back to your topics</a>` back-link to `/`, same `page-header` +
`subtitle` structure used by both `topics.html` and `briefing.html` for the topic name as
the `<h1>`. Non-empty case: the list itself is `briefing.html`'s existing
`.briefing-history`/`.briefing-history__list` markup lifted as-is (same classes, same
`data-timestamp` attribute for `app.js`'s client-side local-time rendering) — not a
new list style, since that markup already matches the rest of the app's list conventions
(see `.topic-list` in `topics.html`). Empty case (`#lists.isEmpty(history)}`): the exact
`.empty-state` block structure from `topics.html:22-25` (message paragraph + a primary
`role="button"`-styled action), substituting a `POST` "Generate briefing" button targeting
`/topics/{topicId}/briefings` for the "Create your first topic" action. No new CSS classes
are needed for this template — if a class doesn't already exist in `app.css`, that's a signal
to reuse an existing one instead of adding one.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "*BriefingServiceTests*"` passes, including new cases for
  `getHistory`: owner with briefings returns the populated list; owner with zero briefings
  returns `Optional.of(TopicHistory)` with an empty summaries list (not `Optional.empty()`);
  a topic ID not owned by the caller (or nonexistent) returns `Optional.empty()`.
- `./gradlew test --tests "*BriefingFlowIntegrationTests*"` passes, including a new case
  covering: `GET /topics/{id}/briefings` for a topic with briefings renders the list with
  working links; the same route for a brand-new topic with zero briefings renders the empty
  state instead of redirecting; the same route for a topic owned by another user redirects to
  `/`.
- `./gradlew test` passes.

#### Manual Verification:

- Navigate directly to `/topics/{id}/briefings` for a topic that already has several
  briefings — confirm the list renders newest-first and each link opens the right briefing.
- Navigate to the same route for a freshly created topic with no briefings yet — confirm the
  empty state appears with a working "Generate briefing" action.
- Visually compare the new page against `topics.html` and `briefing.html` — confirm the
  navbar, back-link, page header, list styling, and empty state all look and behave like the
  rest of the app rather than introducing a distinct look.

**Implementation Note**: After completing this phase and all automated verification passes,
pause here for manual confirmation from the human before proceeding to Phase 2.

---

## Phase 2: Entry point from the topics list

### Overview

Make the new history page reachable from the topics list, and cover the full path end to end.

### Changes Required:

#### 1. Topics list entry point

**File**: `src/main/resources/templates/topics.html`

**Intent**: Give every topic card a persistent, idempotent way into its briefing history that
doesn't also trigger a new generation.

**Contract**: Add a `GET` link (`<a>`, not a form) to `/topics/{id}/briefings` inside each
`.topic-card__actions` block (`topics.html:38-46`), alongside the existing "Generate briefing"
form, "Edit schedule" link, and "Delete" form. Styled identically to the existing "Edit
schedule" link — same `role="button"` + `outline` treatment, a new `.topic-card__history`
class following the same `.topic-card__edit`/`.topic-card__delete` naming convention (add the
matching rule to `app.css`, copying `.topic-card__edit`'s declaration rather than inventing a
new visual style) — so all four actions on a card read as one consistent button group, not a
visually distinct addition. Always rendered, regardless of whether the topic has any briefings
yet — the history page itself (Phase 1) already handles the empty case.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "*TopicFlowIntegrationTests*"` or `*BriefingFlowIntegrationTests*`
  (whichever the implementer finds the more natural home — this route lives in the `briefing`
  module, but the assertion is on `topics.html`'s rendered output) passes a new case asserting
  the topics list page contains a "View briefings" link with the correct `href` for a created
  topic.
- `./gradlew test --tests "*BriefingFlowIntegrationTests*"` passes an extended full-path case:
  create topic → generate a briefing → topics list shows the "View briefings" link → follow it
  to the history page → follow a history entry to its detail view.
- `./gradlew test` passes.

#### Manual Verification:

- From the topics list, click "View briefings" on a real topic and confirm it lands on the
  history page; confirm the link is present and working for a topic with zero briefings too.
- Confirm the new "View briefings" link visually matches the existing "Edit schedule"/"Delete"
  actions on the card (same button style, spacing, alignment) rather than standing out as a
  bolted-on addition.

**Implementation Note**: After completing this phase and all automated verification passes,
pause here for manual confirmation from the human.

---

## Testing Strategy

### Unit Tests:

- `BriefingServiceTests.getHistory` — owner with briefings, owner with zero briefings
  (empty list, not empty Optional), non-owner/nonexistent topic (empty Optional).

### Integration Tests:

- `BriefingFlowIntegrationTests` — direct navigation to the history page (populated and
  empty-state cases), cross-user redirect, and the full topics-list → history → detail path.

### Manual Testing Steps:

1. Create a topic, generate two or three briefings for it, then open its history page directly
   by URL — confirm all entries appear, newest first, and each opens the right briefing.
2. Create a fresh topic with no briefings yet, open its history page — confirm the empty state
   and its "Generate briefing" action work.
3. From the topics list, click "View briefings" for a topic and confirm it lands on the same
   page reached in steps 1-2.
4. As a second user, confirm hitting another user's history URL directly redirects to `/`.

## References

- Prior related work: `context/archive/2026-09-13-first-onboarding-and-delta-briefing/plan.md`
  (built the single-briefing view, the `BriefingSummary` projection, and the inline history
  sidebar this plan extends).
- Roadmap: `context/foundation/roadmap.md` — S-05.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: History page (backend + route + template)

#### Automated

- [x] 1.1 `BriefingServiceTests` passes including new `getHistory` cases — 2c3e205
- [x] 1.2 `BriefingFlowIntegrationTests` passes including new history-page cases — 2c3e205
- [x] 1.3 `./gradlew test` passes — 2c3e205

#### Manual

- [x] 1.4 Direct navigation to the history page for a topic with briefings works — 2c3e205
- [x] 1.5 Direct navigation to the history page for a topic with zero briefings shows the empty state — 2c3e205
- [x] 1.6 New page visually matches topics.html/briefing.html (navbar, back-link, header, list, empty state) — 2c3e205

### Phase 2: Entry point from the topics list

#### Automated

- [x] 2.1 Topics list renders the "View briefings" link with the correct href — 00f2eb5
- [x] 2.2 Full-path integration test (generate → topics list → history → detail) passes — 00f2eb5
- [x] 2.3 `./gradlew test` passes — 00f2eb5

#### Manual

- [x] 2.4 "View briefings" link on the topics list works end to end — 00f2eb5
- [x] 2.5 "View briefings" link visually matches the "Edit schedule"/"Delete" actions on the card — 00f2eb5
