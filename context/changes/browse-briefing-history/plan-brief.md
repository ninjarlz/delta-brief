# Browse Briefing History — Plan Brief

> Full plan: `context/changes/browse-briefing-history/plan.md`

## What & Why

Let a user browse the full history of briefings for a watched topic (FR-011), reachable
independent of generating or viewing a specific briefing. S-03 deliberately deferred this:
it shipped only a minimal inline history sidebar inside the single-briefing view, on the
explicit note that "S-05 owns the real history experience."

## Starting Point

The backend already does almost everything needed: `BriefingRepository.findSummariesByTopicId`
and `BriefingService.listSummaries` return every briefing for a topic as a cheap, ownership-checked
summary projection, and `briefing.html` already renders an inline "History" list built from it.
What's missing is a standalone page reachable without loading one briefing's full content first,
and any link into it from the topics list — today the only way in is generating a new briefing.

## Desired End State

Every topic card on the topics list has a "View briefings" link. Following it shows every
briefing for that topic (title, type, timestamp), newest first, each clickable into the
existing detail view. A topic with no briefings yet shows a friendly empty state with a way
to generate the first one, instead of a dead end.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Shape of the feature | Dedicated history list page (new route) | Matches the archived S-03 plan's own framing that S-05 owns "the real history experience," not just a link into the existing inline sidebar. |
| Empty state | Link always shown; friendly empty state on the page itself | Predictable — the link always does the same thing, and `TopicView` doesn't need a new "hasBriefings" flag just for this. |
| List size | Unbounded, no pagination or cap | PRD marks `data_volume: small`; the underlying query is already unbounded, so this is zero extra work for v1. |
| Row detail | Title + type + timestamp only, reusing `BriefingSummary` | Keeps the list on the existing cheap projection — a content preview would require loading full briefing content per row, an N+1 risk for a nice-to-have. |
| Existing inline sidebar | Left unchanged on `briefing.html` | Zero risk to already-tested S-03 code; the two views serve different moments (quick nav while reading vs. deliberate browsing). |
| Visual design | No new UI vocabulary — copy existing shell, list, empty-state, and button styles verbatim | The user asked for UI/UX consistency with the rest of the app; every element on the new page and its entry-point link already has a direct precedent to reuse (`briefing.html`'s shell and history list, `topics.html`'s empty state and `.topic-card__edit` button style). |

## Scope

**In scope:**
- New `BriefingService.getHistory` method distinguishing "topic not owned" from "topic owned, zero briefings"
- New `GET /topics/{topicId}/briefings` route + `briefing-history.html` template
- "View briefings" link on every topic card in `topics.html`
- Empty-state handling for topics with no briefings yet

**Out of scope:**
- Pagination or a size cap on the list
- Content previews per row
- Any change to `briefing.html`'s existing inline history sidebar
- Any change to briefing generation, classification, or single-briefing rendering

## Architecture / Approach

One new service method (`BriefingService.getHistory`) composes the existing ownership check
with the existing summary query, returning a clean `Optional` the controller can branch on.
One new controller route reuses the existing `toHistoryEntryView`/`HistoryEntryView` helpers
unmodified (passing `null` for "current briefing" so no row is ever marked current). One new
template mirrors `briefing.html`'s existing history list markup and `topics.html`'s existing
empty-state pattern — no new UI vocabulary introduced.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. History page | New service method, route, and template — reachable directly by URL | Conflating "not owned" with "owned, empty" if the new service method isn't used correctly |
| 2. Entry point | "View briefings" link on the topics list + full-path integration test | Low — purely additive template change |

**Prerequisites:** S-03 (done) — briefing generation, the summary projection, and ownership-scoped lookups already exist.

## Open Risks & Assumptions

- Assumes reusing `toHistoryEntryView` with a `null` current-briefing ID is safe (verified: `Object.equals(null)` is always `false`, no NPE risk since the null is the argument, not the receiver).
- Assumes an unbounded list is fine for v1 given "small" data volume; a very long-running, high-frequency topic could eventually make this list large — explicitly deferred, not solved here.

## Success Criteria (Summary)

- A topic's briefing history is reachable directly by URL, independent of generating or viewing any specific briefing.
- A topic with zero briefings shows a working empty state instead of a dead end.
- The topics list has a working, always-visible entry point into each topic's history.
