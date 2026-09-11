---
project: DeltaBrief
version: 1
status: draft
created: 2026-09-10
updated: 2026-09-10
prd_version: 1
main_goal: speed
top_blocker: time
milestone_id: first-delta-loop
milestone_seq: 1
milestone_status: open
---

# Roadmap: DeltaBrief

> Derived from `context/foundation/prd.md` (v1) + auto-researched codebase baseline.
> Edit-in-place; archive when superseded.
> Slices below are listed in dependency order. The "At a glance" table is the index.

## Milestone

**M-1: First working delta loop** — Status: open

- **Intent:** Prove the core loop works end-to-end — a user can create a topic, get an onboarding briefing, and then get a delta briefing that clearly shows what changed — and ship every other must-have capability around that loop.
- **Source materials:** `context/foundation/prd.md` (v1)
- **Done when:** every F-NN and S-NN below is `done`, and the full US-01 loop (create topic → onboarding briefing → delta briefing) works end-to-end for at least one real topic with real source content.
- **Scope anchors:** FR-001 through FR-013 (FR-004 excluded — deferred to v2 in the PRD itself), US-01.
- **External backlog:** [GitHub Project — DeltaBrief M-1](https://github.com/users/ninjarlz/projects/1) (public). Each F-NN/S-NN below is a linked issue (see the `GitHub` column / `GitHub Issue` field); the project's `Roadmap Status` field mirrors each item's `Status` here — the built-in `Status` field is intentionally left unset (its generic Todo/In Progress/Done can't represent `proposed` vs. `blocked` without losing the distinction). **This is a live mirror, not a one-time export:** as slices move through implementation, update the issue and the `Roadmap Status` field alongside this file (see `@AGENTS.md` § Roadmap & backlog) — the roadmap doc, the issue, and the board should never disagree about where an item stands.

## Vision recap

Tracking a long-running news topic today means wading through feeds that show what's newest, not what's changed. DeltaBrief instead compares a topic's current state against the user's last briefing, separating genuine change from noise, trend continuation, and speculation — answering "what changed the picture?" instead of "what's new?"

## North star

**S-03: First onboarding and delta briefing** — the smallest end-to-end flow that proves DeltaBrief's actual value: not that it can summarize (any AI wrapper can), but that it can correctly separate genuine change from noise across two points in time.

> "North star" here means the smallest end-to-end slice that, if it works, shows the product actually delivers on its central idea — placed as early as its Prerequisites allow, because every slice after it only matters if this one actually works.

## At a glance

| ID   | Change ID                       | Outcome (user can …)                                              | Prerequisites | PRD refs               | Status   | GitHub |
| ---- | -------------------------------- | ------------------------------------------------------------------- | -------------- | ----------------------- | -------- | ------ |
| F-01 | wire-database-connectivity       | (foundation) app connects to a real Postgres DB, locally and deployed | —              | Access Control, NFR: user data privacy | in-progress | [#5](https://github.com/ninjarlz/delta-brief/issues/5) |
| S-01 | user-registration-and-login      | register (email/password or OAuth) and log in/out                   | F-01           | FR-001, FR-002          | proposed | [#6](https://github.com/ninjarlz/delta-brief/issues/6) |
| S-02 | create-topic-and-select-sources  | create a watched topic, pick its sources, browse their topic list   | S-01           | FR-003, FR-005, FR-006  | blocked  | [#7](https://github.com/ninjarlz/delta-brief/issues/7) |
| S-03 | first-onboarding-and-delta-briefing | trigger an onboarding briefing, then a delta briefing, and read both | S-02           | FR-007, FR-009 (manual trigger), FR-010, US-01 | proposed | [#8](https://github.com/ninjarlz/delta-brief/issues/8) |
| S-04 | scheduled-briefing-generation    | set a briefing frequency and get delta briefings automatically      | S-03           | FR-008, FR-009 (schedule) | proposed | [#9](https://github.com/ninjarlz/delta-brief/issues/9) |
| S-05 | browse-briefing-history          | browse the history of briefings for a topic                         | S-03           | FR-011                  | proposed | [#10](https://github.com/ninjarlz/delta-brief/issues/10) |
| S-06 | email-briefing-delivery          | opt in to receive briefings via email                                | S-03           | FR-012                  | proposed | [#11](https://github.com/ninjarlz/delta-brief/issues/11) |
| S-07 | rate-a-briefing                  | rate a briefing with predefined categories                          | S-03           | FR-013                  | proposed | [#12](https://github.com/ninjarlz/delta-brief/issues/12) |

## Streams

Navigation aid — groups items that share a Prerequisites chain. Canonical ordering still lives in the dependency graph below; this table is the proposed reading order across parallel tracks.

| Stream | Theme                    | Chain                              | Note                                                                 |
| ------ | ------------------------ | ----------------------------------- | --------------------------------------------------------------------- |
| A      | Prove the core loop      | `F-01` → `S-01` → `S-02` → `S-03`  | The single path to the north star — everything else forks from here.  |
| B      | Automation                | `S-04`                              | Joins Stream A at `S-03`. Deferred from the north star per the speed goal — US-01 allows manual triggering. |
| C      | Briefing history          | `S-05`                              | Joins Stream A at `S-03`. Independent of B, D, E — parallelizable.     |
| D      | Delivery                  | `S-06`                              | Joins Stream A at `S-03`. Independent of B, C, E — parallelizable.     |
| E      | Feedback loop             | `S-07`                              | Joins Stream A at `S-03`. Independent of B, C, D — parallelizable.     |

## Baseline

What's already in place in the codebase as of `2026-09-10` (verified directly — the deploy layer was built and confirmed live in the same working session as this roadmap).
Foundations below assume these are present and do NOT re-scaffold them.

- **Frontend:** partial — `spring-boot-starter-thymeleaf` wired; one throwaway placeholder page (`PlaceholderController` → `placeholder.html`) proving the deploy pipeline. No real UI for any FR yet.
- **Backend / API:** partial — Spring Boot 4 (Java 21) deployed and live at `https://delta-brief.onrender.com`. No public REST API by design (server-rendered only, per `tech-stack.md`). Zero business-logic code for any bounded context.
- **Data:** absent — Supabase Postgres chosen in `tech-stack.md`, but no JDBC/JPA dependency, no datasource config, no Supabase project created yet.
- **Auth:** partial — `spring-boot-starter-security` present; a `SecurityFilterChain` exists but only permits `/` and `/actuator/health` — no login mechanism, no user table, no registration flow.
- **Deploy / infra:** present — `Dockerfile`, `render.yaml`, and `.github/workflows/ci-cd.yml` (build-test gate + auto-deploy-on-merge via Render deploy hook) are committed and verified live.
- **Observability:** partial — Actuator health endpoint only (`/actuator/health`); no logging framework beyond Spring Boot defaults, no error tracking.

## Foundations

### F-01: Wire database connectivity

- **Outcome:** (foundation) The app connects to a real Postgres (Supabase) database, both locally and in the deployed Render environment, with a schema-migration mechanism in place so future slices can add tables incrementally.
- **Change ID:** wire-database-connectivity
- **GitHub Issue:** [#5](https://github.com/ninjarlz/delta-brief/issues/5)
- **PRD refs:** Access Control, NFR: user data privacy ("User data ... is private to each user. No cross-user leakage.")
- **Unlocks:** S-01 (needs a users table); transitively, every other slice in this milestone.
- **Prerequisites:** —
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Every other slice needs real persistence — sequenced first so S-01 onward isn't built against throwaway in-memory state that would need reworking later. Creating the actual Supabase project is part of this slice's own execution, not a precondition to starting it.
- **Status:** in-progress

## Slices

### S-01: User registration and login

- **Outcome:** User can create an account (email/password or OAuth) and log in and out.
- **Change ID:** user-registration-and-login
- **GitHub Issue:** [#6](https://github.com/ninjarlz/delta-brief/issues/6)
- **PRD refs:** FR-001, FR-002
- **Prerequisites:** F-01
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Sequenced before topics/briefings because the PRD's access-control model makes all downstream data per-user from day one — building topics/briefings first would mean retrofitting ownership onto existing data later.
- **Status:** proposed

### S-02: Create topic and select sources

- **Outcome:** User can create a watched topic, pick its sources from presets, and browse their topic list.
- **Change ID:** create-topic-and-select-sources
- **GitHub Issue:** [#7](https://github.com/ninjarlz/delta-brief/issues/7)
- **PRD refs:** FR-003, FR-005, FR-006
- **Prerequisites:** S-01
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:**
  - What are the preset source lists per topic category (the actual curated feeds/sources)? — Owner: user. Block: yes.
- **Risk:** Blocked on a real content decision only the user can make (source curation). Sequencing it right after auth surfaces that gap now, rather than discovering it mid-build of the north star, which needs real source content to generate anything against.
- **Status:** blocked

### S-03: First onboarding and delta briefing

- **Outcome:** User can trigger generation of an onboarding briefing for a new topic, then trigger a delta briefing that compares new content against it, and read both in the app.
- **Change ID:** first-onboarding-and-delta-briefing
- **GitHub Issue:** [#8](https://github.com/ninjarlz/delta-brief/issues/8)
- **PRD refs:** FR-007, FR-009 (manual-trigger portion only — see S-04 for the scheduled portion), FR-010, US-01
- **Prerequisites:** S-02
- **Parallel with:** —
- **Blockers:** —
- **Unknowns:**
  - How does the onboarding briefing differ from a delta briefing in structure and length? — Owner: user. Block: no.
- **Risk:** This is the go/no-go slice for the whole product — if the delta-classification (genuine change vs. trend vs. noise/speculation) doesn't hold up, later slices would be automating and distributing something unproven. Deliberately scoped to a **manual** trigger, not the full `@Scheduled` automation — US-01 explicitly allows "manually or on schedule," so this shrinks the north star and gets an answer faster. Scheduling is deferred to S-04.
- **Status:** proposed

### S-04: Scheduled briefing generation

- **Outcome:** User can set a briefing frequency (manual, twice daily, daily, every other day, weekly) and receive delta briefings automatically on that schedule.
- **Change ID:** scheduled-briefing-generation
- **GitHub Issue:** [#9](https://github.com/ninjarlz/delta-brief/issues/9)
- **PRD refs:** FR-008, FR-009 (scheduled-trigger portion)
- **Prerequisites:** S-03
- **Parallel with:** S-05, S-06, S-07
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Depends on S-03's generation logic already working manually — adding scheduling on top of unproven generation would compound two unknowns (does generation work? does the schedule fire reliably?) into one slice that's harder to debug if something goes wrong.
- **Status:** proposed

### S-05: Browse briefing history

- **Outcome:** User can browse the history of briefings for a watched topic.
- **Change ID:** browse-briefing-history
- **GitHub Issue:** [#10](https://github.com/ninjarlz/delta-brief/issues/10)
- **PRD refs:** FR-011
- **Prerequisites:** S-03
- **Parallel with:** S-04, S-06, S-07
- **Blockers:** —
- **Unknowns:** —
- **Risk:** Low-risk, standard read/list capability. Sequenced after S-03 only because there's nothing to browse until at least one briefing exists.
- **Status:** proposed

### S-06: Email briefing delivery

- **Outcome:** User can opt in to receive generated briefings via email.
- **Change ID:** email-briefing-delivery
- **GitHub Issue:** [#11](https://github.com/ninjarlz/delta-brief/issues/11)
- **PRD refs:** FR-012
- **Prerequisites:** S-03
- **Parallel with:** S-04, S-05, S-07
- **Blockers:** —
- **Unknowns:**
  - What is the email briefing format (plain text vs. styled), and the opt-in/opt-out flow details? — Owner: user. Block: no.
- **Risk:** Depends on a real briefing existing to send. The exact email format is still an open PRD question, so this slice's scope may narrow once that's answered — but the delivery mechanism itself can be planned regardless.
- **Status:** proposed

### S-07: Rate a briefing

- **Outcome:** User can rate a briefing using the predefined categories (useful, too much noise, too little context, already knew this, not relevant to me).
- **Change ID:** rate-a-briefing
- **GitHub Issue:** [#12](https://github.com/ninjarlz/delta-brief/issues/12)
- **PRD refs:** FR-013
- **Prerequisites:** S-03
- **Parallel with:** S-04, S-05, S-06
- **Blockers:** —
- **Unknowns:**
  - How are briefing ratings used in v1 (the feedback-loop mechanics)? — Owner: user. Block: no.
- **Risk:** Capturing the rating itself is safe to build regardless of the open question above — "how ratings feed back into prompts" is a separate, unblocked downstream concern that doesn't gate collecting the data now.
- **Status:** proposed

## Backlog Handoff

| Roadmap ID | Change ID                          | Suggested issue title                                   | Ready for `/10x-plan` | Notes                                              |
| ---------- | ------------------------------------ | ---------------------------------------------------------- | ---------------------- | --------------------------------------------------- |
| F-01       | wire-database-connectivity           | Wire Supabase Postgres connectivity + migrations           | yes                    | —                                                   |
| S-01       | user-registration-and-login          | User registration and login                                | no                     | Waiting on F-01                                     |
| S-02       | create-topic-and-select-sources      | Create topic and select sources                             | no                     | Blocked — preset source lists not yet defined (user) |
| S-03       | first-onboarding-and-delta-briefing  | First onboarding + delta briefing (manual trigger)          | no                     | Waiting on S-02                                     |
| S-04       | scheduled-briefing-generation        | Scheduled briefing generation                                | no                     | Waiting on S-03                                     |
| S-05       | browse-briefing-history              | Browse briefing history                                      | no                     | Waiting on S-03                                     |
| S-06       | email-briefing-delivery              | Email briefing delivery                                       | no                     | Waiting on S-03; email format still open (Q3)       |
| S-07       | rate-a-briefing                      | Rate a briefing                                               | no                     | Waiting on S-03; ratings usage still open (Q4)      |

## Open Roadmap Questions

1. **What are the preset source lists per topic category?** — Owner: user. Block: S-02 (and transitively everything after it).
2. **How does the onboarding briefing differ from a delta briefing in structure and length?** — Owner: user. Block: no (informs S-03's implementation).
3. **What is the email briefing format (plain text vs. styled), and the opt-in/opt-out flow?** — Owner: user. Block: no (informs S-06).
4. **How are briefing ratings used in v1 (feedback-loop mechanics)?** — Owner: user. Block: no (informs S-07).
5. ~~Is there a new target date for this milestone?~~ — **Resolved 2026-09-10.** New MVP target: **2026-10-14, 21:00** (supersedes the PRD's `hard_deadline: 2026-07-31`). The user has near-term full-time capacity for the next few days — a strong window for pushing through F-01 → S-01 → S-02 in one push. This is recorded as fact, not a scheduling rule: sequencing in this roadmap is still governed by dependency order (per-milestone dates aren't tracked here — see `context/foundation/prd.md` if that frontmatter is worth updating too).

## Parked

- **Optional topic-description field (FR-004)** — Why parked: the PRD itself deferred this to v2 ("does anyone fill in optional fields in v1? If the AI doesn't use it, it's a dead field").
- **Deactivate / stop watching a topic (FR-014)** — Why parked: the sole `nice-to-have` priority FR in the PRD; speed-focused sequencing defers non-essentials rather than sequencing them late.
- **Custom RSS feed management** — Why parked: PRD Non-Goal — removes feed-parsing/validation complexity; source curation is manual for v1.
- **Real-time alerts or push notifications** — Why parked: PRD Non-Goal — contradicts the reduce-noise mission; briefings are periodic by design.
- **Multi-user collaboration or shared topics** — Why parked: PRD Non-Goal — keeps the data model and access control simple for MVP.
- **Source bias or credibility scoring** — Why parked: PRD Non-Goal — a separate hard problem; attempting it in MVP risks misleading users.

## Milestone History

(empty — first milestone)

## Done

(empty — nothing archived yet)
