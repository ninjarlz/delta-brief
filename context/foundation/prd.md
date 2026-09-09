---
project: "DeltaBrief"
version: 1
status: draft
created: 2026-05-29
context_type: greenfield
product_type: web-app
target_scale:
  users: medium
  qps: low
  data_volume: small
timeline_budget:
  mvp_weeks: 5
  hard_deadline: 2026-07-31
  after_hours_only: true
---

## Vision & Problem Statement

Tracking long-running news topics — wars, politics, economics, regulations, international relations — is time-consuming and overstimulating. Classic news feeds and aggregators show what's newest, most commented, or most clickable. A news-literate individual who can't check a topic daily doesn't need another list of news. They need the answer to: **what actually changed since the last time I looked?**

Existing tools (feeds, digests, AI summaries) answer "what's new?" — DeltaBrief answers "what changed the picture?" That reframe changes the entire output shape. Instead of a chronological feed, DeltaBrief produces a delta briefing that compares the current state of a topic against the prior briefing, separating real change from noise, trend continuation, and speculation. No existing tool does this.

## User & Persona

### Primary persona

News-literate individuals across many contexts — people who follow geopolitics, economics, domestic politics, or major societal events but are not journalists, analysts, or professionals paid to track news. They care about understanding the evolution of complex topics without the daily time investment. The moment they reach for this product: they return to a topic after days or a week, open a news source, and face a wall of repetition, commentary, and speculation with no way to tell what genuinely shifted the situation. The cost today: 30+ minutes of scrolling to mentally reconstruct the delta, or giving up and staying uninformed.

## Success Criteria

### Primary
- User can create a topic, receive an onboarding briefing, and then receive a delta briefing that clearly shows what changed — the full end-to-end flow works.

### Secondary
- User returns to generate a second briefing for the same topic (retention signal — the first briefing was useful enough to come back).

### Guardrails
- Briefings must not fabricate facts. All claims in a briefing must be traceable to the sources that were ingested. AI hallucination is the #1 trust risk for this product.

## User Stories

### US-01: First delta briefing for a new topic

- **Given** a logged-in user with no watched topics
- **When** they create a topic "War in Ukraine", select preset sources, and trigger briefing generation
- **Then** they receive an onboarding briefing summarizing the current state of the topic

- **Given** the same user, days later, with new content available from the selected sources
- **When** the next briefing generates (manually or on schedule)
- **Then** they receive a delta briefing that clearly separates: what genuinely changed, what is trend continuation, what is noise/speculation, why the changes matter, and what remains uncertain — all traceable to sources

## Functional Requirements

### Authentication
- FR-001: User can create an account (email + password or OAuth). Priority: must-have
  > Socrates: Counter-argument considered: "Requiring account creation before seeing any value creates friction — a demo briefing would reduce it." Resolution: kept; briefings are personal (topic choices, history), auth-first is the right trade.
- FR-002: User can log in and log out. Priority: must-have
  > Socrates: Standard capability. No counter-argument; stands as written.

### Topic Management
- FR-003: User can create a watched topic by providing a name (e.g. "War in Ukraine"). Priority: must-have
  > Socrates: Standard capability. No counter-argument; stands as written.
- ~~FR-004: User can add an optional description of their observation goal for a topic. Priority: must-have~~ **DEFERRED to v2.** Socrates: "Does anyone fill in optional fields in v1? If the AI doesn't use it, it's a dead field." Resolution: deferred — prove the core flow first, add personalization when it's worth it.
- FR-005: User can browse their list of watched topics. Priority: must-have
  > Socrates: Standard capability. No counter-argument; stands as written.

### Source Selection
- FR-006: User can select from preset/predefined sources for a topic. Priority: must-have
  > Socrates: Counter-argument considered: "Preset sources require editorial curation before launch — that's work outside engineering." Resolution: kept; user will curate sources manually for v1 across a small number of topic categories.

### Briefing Generation
- FR-007: User can trigger generation of an onboarding briefing for a newly created topic (initial state summary, longer than subsequent delta briefings). Priority: must-have
  > Socrates: Counter-argument considered: "The onboarding briefing is a separate AI pipeline. A delta-from-empty-baseline eliminates a separate generation path." Resolution: kept; the first briefing needs to set the stage — a delta-from-nothing doesn't give the user enough context to understand future deltas.
- FR-008: User can set the briefing frequency for a topic (manual, twice daily, daily, every other day, weekly). Priority: must-have
  > Socrates: Counter-argument considered: "Five frequency options is significant scheduling infrastructure. Manual + daily covers 80% of use cases." Resolution: kept; different topics need different cadences (war = daily, policy = weekly).
- FR-009: App generates delta briefings on the configured schedule, comparing new content against the previous briefing for the same topic. Priority: must-have
  > Socrates: Counter-argument considered: "Scheduled generation means background jobs, cron, retry handling. Manual-only for v1 would prove quality first." Resolution: kept; without scheduling, users must remember to open the app — that recreates the scroll-or-forget problem DeltaBrief solves.

### Briefing Content
- FR-010: User can read generated briefings in the web app, with structured sections: key changes, trend continuation, noise/speculation, significance, uncertainties, source impact on scenarios, and sources. Priority: must-have
  > Socrates: Counter-argument considered: "Seven mandatory sections is rigid — some briefings might have nothing to say in some sections." Resolution: kept as template with empty sections allowed; consistency matters — users learn the structure. Empty sections show "No noise detected" etc.
- FR-011: User can browse the history of briefings for each watched topic. Priority: must-have
  > Socrates: Standard capability. No counter-argument; stands as written.

### Delivery
- FR-012: User can opt to receive generated briefings via email. Priority: must-have
  > Socrates: Counter-argument considered: "Email delivery means email service integration, deliverability, HTML formatting, unsubscribe flows — significant work." Resolution: kept; email is core to the value prop — briefings reaching users who don't open the app is the whole point.

### Feedback
- FR-013: User can rate a briefing with predefined categories (useful, too much noise, too little context, already knew this, not relevant to me). Priority: must-have
  > Socrates: Counter-argument considered: "Detailed categories only matter if the feedback loop is closed. Thumbs up/down might suffice for v1." Resolution: kept; even without automation in v1, detailed categories give actionable data for manual prompt improvement.

### Topic Lifecycle
- FR-014: User can stop watching a topic (deactivate briefing generation). Priority: nice-to-have
  > Socrates: No counter-argument; nice-to-have is the right priority level.

## Non-Functional Requirements

- Briefing generation provides continuous visible feedback during processing; the user is never left wondering whether generation is still running or has failed. Generation completes within a timeframe that makes the product usable as a routine information tool.
- User data (topics, briefing history, ratings) is private to each user. No cross-user leakage, no public exposure of personal topic selections or briefing contents.
- A delta briefing is readable in under 3 minutes. If the output exceeds this, it recreates the noise problem DeltaBrief exists to solve.

## Business Logic

DeltaBrief compares new source content against the user's last briefing to classify each piece of information as a genuine change to the situation, a continuation of an existing trend, or noise/speculation.

The rule consumes two inputs: (1) new content from the user's selected sources for a topic, ingested since the last briefing was generated; (2) the user's previous briefing for that topic, which serves as the baseline state representing "what the user already knows."

The rule produces a classified briefing where each significant piece of information is tagged into one of three categories — genuine change, trend continuation, or noise/speculation — accompanied by a significance explanation, key uncertainties, impact on possible scenarios, and source attributions.

The user encounters this rule as the delta briefing they read in the web app or receive via email: a structured document that answers "what changed the picture since last time?" instead of "what's new?"

## Access Control

Login via email + password or OAuth. Standard web app accounts — needed to store topics, preferences, and briefing history per user. Flat user model: all users have the same capabilities. No admin panel, no role separation in MVP.

## Non-Goals

- **No custom RSS feed management.** Users pick from preset/curated sources only. No feed URL entry, validation, or parsing in MVP. Rationale: removes complex feed management infrastructure; source curation is manual for v1.
- **No real-time alerts or push notifications.** Briefings are periodic (scheduled or on-demand), not instant. No breaking-news alerts, no mobile push. Rationale: DeltaBrief is about understanding, not speed. Real-time alerts contradict the "reduce noise" mission.
- **No multi-user collaboration or shared topics.** Topics are personal. No team workspaces, shared briefings, or collaborative watching. Rationale: single-user focus keeps the data model and access control simple for MVP.
- **No source bias or credibility scoring.** No automated assessment of source reliability, political lean, or trustworthiness. Sources are curated manually. Rationale: credibility scoring is a separate hard problem; attempting it in MVP risks misleading users.
- **No observation goal personalization (deferred to v2).** Users cannot add a description of why they're tracking a topic. Rationale: prove the core delta briefing flow works before adding personalization inputs.

## Open Questions

1. **What are the preset source lists per topic category?** — The user committed to curating sources manually for v1. The actual source lists (which RSS feeds / news sources per topic category) need to be defined before launch. Owner: user. Block: yes (generation pipeline needs sources to ingest).
2. **How does the onboarding briefing differ from a delta briefing in structure and length?** — The onboarding briefing is described as "longer than subsequent delta briefings" and serves as an initial state summary. The exact format, length constraints, and how it relates to the delta briefing template need definition. Owner: user. Block: no (can iterate during implementation).
3. **What is the email briefing format?** — Email delivery is must-have, but the format (plain text vs. styled), frequency of email vs. in-app generation, and opt-in/opt-out flow details are unspecified. Owner: user. Block: no (can iterate during implementation).
4. **How are briefing ratings used in v1?** — Detailed rating categories are captured for "manual prompt improvement," but the feedback loop mechanics (how ratings inform future briefing generation) are undefined for v1. Owner: user. Block: no (ratings can be collected without a closed loop initially).
