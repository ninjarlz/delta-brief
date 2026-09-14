# Email Briefing Delivery Implementation Plan

## Overview

Let a user opt in per topic to receive newly generated briefings by email (FR-012),
reusing the existing `EmailSender`/Resend infrastructure and the single shared success
point in `BriefingService.generateBriefing` that already unifies every generation
trigger — manual "Generate now" and the scheduled poll job alike.

## Current State Analysis

- `pl.tul.deltabrief.shared.application.EmailSender` (`send(to, subject, body)`) and its
  `ResendSmtpEmailSender` adapter already exist and are wired to Resend's SMTP relay, but
  their only caller today is `RegistrationService`'s account-verification email. The
  port's own Javadoc already anticipates this feature: *"S-06 (email-briefing-delivery)
  reuses this same abstraction and Resend account."*
- The entire email stack is plain-text only — `ResendSmtpEmailSender` builds a
  `SimpleMailMessage` (no `MimeMessage`/HTML capability anywhere in the codebase).
- `RegistrationService.resendVerification` is the one existing `@Async("emailTaskExecutor")`
  method; `AsyncConfig.emailTaskExecutor()` (core=1, max=2, queue=50, graceful shutdown)
  backs it, gated by `app.async.email.enabled` (default true; tests set it `false` and
  `@Import(SynchronousAsyncConfig.class)` to run synchronously and deterministically).
- `BriefingService.generateBriefing(TopicId, UserId)` is called identically by
  `BriefingController.generate` (manual, synchronous HTTP path) and
  `ScheduledBriefingRunner.generateOne` (scheduled, bounded worker pool) — both converge
  on the same `briefingRepository.save(briefing)` line, immediately followed by
  `topicRepository.recordSuccessfulGeneration(...)`. This is already the established
  single hook point for "do this on every successful generation, regardless of trigger."
- Nothing today can resolve a user's email address from just a `UserId`.
  `TopicSummary`/`DueTopic` (the cross-module projections `briefing` already consumes)
  don't carry it, and `UserRepository` exposes only `save`/`findByEmail`/
  `findByVerificationToken`/`existsByEmail` — no `findById`.
- `Topic` has no email-delivery field. The precedent for adding one is `frequency`/
  `preferredHour`: a migration, a new `Topic` field, a single mutator
  (`applySchedule`) called from both `TopicService.createTopic` and
  `TopicService.updateSchedule`, threaded through `CreateTopicRequest`/
  `EditScheduleRequest` and both `topic-form.html`/`topic-edit.html`.
- `BriefingController` currently owns the citation-renumbering logic (`CITATION_PATTERN`,
  `citedNumbersInOrder`, `renumbering`, `renumberCitations`, `citedSources`) as private
  static methods, used to turn a `Briefing`'s raw `[n]`-marked content into the exact
  numbered source list the web page shows.

### Key Discoveries:

- **`UserId`'s own Javadoc is explicit**: *"Other modules reference a user only by this
  value — never by importing `User` itself."* Adding a `UserRepository` method that
  returns the full `User` aggregate for `briefing.application` to consume would violate
  this. The fix mirrors `TopicSummary`'s existing pattern: a narrow new port method
  returning just the email string, e.g. `Optional<String> findEmailById(UserId id)` —
  `UserJpaRepository` already inherits a by-ID lookup from `JpaRepository`, so the
  adapter implementation is a one-line `.map(UserJpaEntity::getEmail)`.
- **`@Async` self-invocation would silently break this feature if placed carelessly.**
  Spring's proxy-based AOP does not intercept a bean calling its own method internally —
  only external calls through the proxy are async. Since the natural trigger point is
  inside `BriefingService.generateBriefing` itself, the `@Async` email-sending method
  must live on a *separate* Spring bean that `BriefingService` calls into (exactly how
  `resendVerification` already lives on `RegistrationService`, invoked from a different
  bean) — never as a method on `BriefingService` that `generateBriefing` calls directly.
- **The citation-renumbering logic needs one shared home, not two copies.** The emailed
  briefing must show the same renumbered citations as the web page for the same
  briefing — reusing `BriefingController`'s private methods isn't possible (they're
  private, and `application`/a new email-notifier class must not depend on
  `adapter.in.web` anyway). The correct fix is extracting this pure, framework-free logic
  (it only touches `Briefing`/`IngestedItem`, both already in `briefing.domain`) into a
  new `briefing.domain.CitationRenderer`, which `BriefingController` and the new email
  path both call — one implementation, provably consistent output.

## Desired End State

A topic's create and edit forms show an "email me new briefings" checkbox, checked by
default with a "(recommended)" label. When a briefing is generated for an opted-in topic
— by any trigger, manual or scheduled, onboarding or delta — the owner receives a
plain-text email with the briefing's full content (all sections + cited sources) shortly
after, sent asynchronously so it never blocks the generation request or the scheduler's
dispatch. Opting out is done the same way opting in was: unchecking the box on the topic's
edit-schedule form.

**Verification**: `./gradlew test` passes; manually create a topic (opted in by default),
generate a briefing, and confirm an email arrives with the briefing's content; manually
uncheck the opt-in on an existing topic, generate again, and confirm no email is sent for
that generation.

## What We're NOT Doing

- No HTML/styled email — plain text only, matching the entire existing email stack. No
  new `MimeMessage`/`MimeMessageHelper` capability is introduced.
- No account-wide opt-in toggle — the setting is per-topic, matching how every other
  delivery preference (frequency, preferred hour) already works per-topic.
- No one-click/tokenized unsubscribe link — opting out is unchecking the same checkbox
  on the edit-schedule form, the same way frequency itself is already changed today.
- No retry-on-failure for a dropped email send — a failed send is logged and swallowed,
  identical to how `sendVerificationEmail` already treats delivery failure as non-fatal.
- No condensed/summarized email content — the full briefing (all six sections + sources)
  is included inline, matching the "no need to open the app" value proposition.
- No visible "email enabled" badge on the topics list — the setting is only shown/edited
  on the create and edit-schedule forms, keeping `topics.html` unchanged.

## Implementation Approach

Land the opt-in data model and forms first (independently verifiable — the setting
persists and displays correctly with no email behavior yet), then wire actual delivery
in a second phase that extracts the shared citation-rendering logic, adds the missing
user-email lookup, and hooks a new async email-sending collaborator into
`generateBriefing`'s existing shared success path.

## Critical Implementation Details

### Timing & lifecycle

The email-sending method must be `@Async` on a dedicated collaborator bean (this plan
calls it `BriefingEmailNotifier`), injected into and called from `BriefingService`
— never a method on `BriefingService` that it calls on itself. Self-invocation bypasses Spring's
AOP proxy entirely, which would silently make the "async" send run synchronously and
block both the manual HTTP response and the scheduler's per-topic dispatch, defeating
the whole point of the async decision.

## Phase 1: Per-topic email opt-in

### Overview

Add the schema, domain field, and form fields for the opt-in setting. No email is sent
yet — this phase is verifiable purely by the setting persisting and displaying correctly.

### Changes Required:

#### 1. Schema migration

**File**: `src/main/resources/db/migration/V14__add_topic_email_enabled_column.sql`

**Intent**: Add the opt-in column, defaulting new *and* existing topics to opted-in —
a deliberate, explicit default (not conservative), consistent with V13's own precedent
of backfilling every existing topic to a real, product-chosen default rather than an
inert placeholder.

**Contract**: `ALTER TABLE topics ADD COLUMN email_enabled BOOLEAN NOT NULL DEFAULT true;`
— the `DEFAULT true` clause both sets the value for all existing rows and becomes the
column's default for future inserts that don't specify it.

#### 2. `Topic` domain

**File**: `src/main/java/pl/tul/deltabrief/topic/domain/Topic.java`

**Intent**: Represent the opt-in state on the aggregate, set at creation and changeable
via the same mutator that already handles the other schedule-adjacent fields.

**Contract**: New field `boolean emailEnabled`. `Topic.create(...)` defaults it to
`true`. Extend `applySchedule(Frequency frequency, Integer preferredHour, boolean
emailEnabled, Instant nextDueAt)` — one more parameter on the existing mutator, since
this field changes at exactly the same two call sites (`TopicService.createTopic`,
`TopicService.updateSchedule`) as `frequency`/`preferredHour` already do.

#### 3. Persistence mapping

**Files**: `src/main/java/pl/tul/deltabrief/topic/adapter/out/persistence/TopicJpaEntity.java`,
`TopicEntityMapper.java`

**Intent**: Persist the new field alongside the others.

**Contract**: `TopicJpaEntity` gains `@Column(name = "email_enabled", nullable = false)
private boolean emailEnabled;`. `TopicEntityMapper.toEntity` gains the corresponding
explicit `@Mapping` line (mirroring `frequency`/`preferredHour`'s pattern); `toDomain`
continues to map implicitly since `TopicJpaEntity`'s getter name matches `Topic`'s
constructor parameter name.

#### 4. Cross-module summary projection

**Files**: `src/main/java/pl/tul/deltabrief/topic/application/port/out/TopicSummary.java`,
`TopicRepository.java` (Javadoc only), `adapter/out/persistence/TopicRepositoryAdapter.java`,
`adapter/out/persistence/TopicJpaRepository.java`

**Intent**: Make the opt-in flag available to `briefing` the same way name/category/
description already are — this is what Phase 2's email trigger will read.

**Contract**: `TopicSummary` gains a `boolean emailEnabled` component.
`TopicJpaRepository.TopicNameAndCategoryView` gains `boolean getEmailEnabled()`.
`TopicRepositoryAdapter.findSummaryByIdAndUserId` maps the new projection field through
into `TopicSummary`.

#### 5. Create and edit forms

**Files**: `src/main/java/pl/tul/deltabrief/topic/application/dto/CreateTopicRequest.java`,
`EditScheduleRequest.java`, `src/main/resources/templates/topic-form.html`,
`topic-edit.html`

**Intent**: Let the user see and change the opt-in setting at creation and later.

**Contract**: Both DTOs gain `private boolean emailEnabled = true;` (mirrors
`CreateTopicRequest.frequency`'s own `= Frequency.DAILY` default-on-fresh-render
pattern — `EditScheduleRequest`'s field is overwritten by the pre-populated value when
the edit form loads, same as its existing fields). Both templates get a checkbox field
labeled along the lines of "Email me new briefings for this topic (recommended)".
Pico's checkbox convention wraps the input *inside* the label (`<label><input
type="checkbox" .../> text</label>`) — different from this file's existing text/select/
textarea fields, which wrap the input the other way; using the wrong pattern here would
render but look inconsistent with Pico's own checkbox styling.

#### 6. Wire the DTO field through the service layer

**Files**: `src/main/java/pl/tul/deltabrief/topic/application/TopicService.java`,
`adapter/in/web/TopicController.java`

**Intent**: Thread the new field from form to domain, same as `frequency`/`preferredHour`
already are.

**Contract**: `TopicService.createTopic`'s full overload and `updateSchedule` both gain
a `boolean emailEnabled` parameter, passed straight into `applySchedule`.
`TopicController.createTopic`/`updateSchedule` pass `form.isEmailEnabled()` through
at the same call sites `form.getFrequency()`/`form.getPreferredHour()` already are.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "*TopicTests*"` passes, including a new case for
  `Topic.applySchedule` covering the `emailEnabled` parameter.
- `./gradlew test --tests "*TopicServiceTests*"` passes, including new cases: creating a
  topic defaults `emailEnabled` to `true`; `updateSchedule` can flip it to `false` and
  back.
- `./gradlew test --tests "*TopicRepositoryAdapterTests*"` passes, including a case
  asserting `findSummaryByIdAndUserId` returns the correct `emailEnabled` value.
- `./gradlew build` succeeds (migration applies cleanly, including the backfill of
  existing rows to `true`).

#### Manual Verification:

- Create a new topic — confirm the "email me new briefings" checkbox is checked by
  default and its "(recommended)" label is visible.
- Uncheck it, save, then reopen the edit-schedule form — confirm the unchecked state
  persisted.
- Re-check it and save — confirm it persists as checked.

**Implementation Note**: After completing this phase and all automated verification
passes, pause here for manual confirmation from the human before proceeding to Phase 2.

---

## Phase 2: Send the briefing by email when opted in

### Overview

Wire actual delivery: extract the shared citation-rendering logic, add the missing
user-email lookup, and hook a new async email-sending collaborator into
`generateBriefing`'s existing shared success path so every generation trigger sends the
email uniformly.

### Changes Required:

#### 1. Shared citation renderer

**File**: `src/main/java/pl/tul/deltabrief/briefing/domain/CitationRenderer.java` (new)

**Intent**: Give the web page and the new email path one shared, provably-consistent
implementation of "renumber this briefing's inline citations and list only the sources
actually cited" — extracted out of `BriefingController`, which currently owns this
logic privately.

**Contract**: Move `CITATION_PATTERN`, `citedNumbersInOrder`, `renumbering`,
`renumberCitations`, and the cited-items-selection logic (currently `citedSources`,
which builds web-specific `SourceView`s — the extracted version returns the cited
`List<IngestedItem>` in order instead, letting each caller map to its own display
shape) out of `BriefingController` into this new class, unchanged in behavior. Expose
one method bundling the result, e.g. `static RenderedBriefing render(Briefing briefing)`
returning the six renumbered section strings plus the ordered cited `IngestedItem` list.
`BriefingController.toView` calls this and maps the cited items into `SourceView` itself;
`BriefingControllerTests`' existing citation-behavior tests continue to pass unchanged
since `toView`'s observable behavior doesn't change.

#### 2. User email lookup

**Files**: `src/main/java/pl/tul/deltabrief/auth/application/port/out/UserRepository.java`,
`adapter/out/persistence/UserRepositoryAdapter.java`

**Intent**: Resolve a recipient email address from a `UserId` without exposing the
`User` aggregate outside the `auth` module.

**Contract**: New port method `Optional<String> findEmailById(UserId id)`. Adapter
implementation: `jpaRepository.findById(id.value()).map(UserJpaEntity::getEmail)` —
`UserJpaRepository` already inherits `findById` from `JpaRepository`, no new query
method needed there.

#### 3. Briefing email notifier

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/BriefingEmailNotifier.java` (new)

**Intent**: The dedicated, separately-invoked bean that actually sends the email —
kept apart from `BriefingService` for the `@Async` self-invocation reason above.

**Contract**: A `@Component`/`@Service` depending on `EmailSender` and (the `auth`
module's) `UserRepository`. One public method, `@Async("emailTaskExecutor") void
sendBriefingEmail(UserId userId, String topicName, Briefing briefing)`. Looks up the
recipient via `userRepository.findEmailById(userId)`; if absent, logs and returns (a
defensive case that shouldn't occur in practice). Otherwise builds the email using
`CitationRenderer.render(briefing)` — subject along the lines of `"{topicName} —
{Onboarding|Delta} briefing"`, body containing all six labeled sections, the cited
sources list, and a link back to the briefing's page in the app (`{app.base-url}/topics/
{topicId}/briefings/{briefingId}`, mirroring `RegistrationService`'s existing use of
`app.base-url` for its verification link). Wraps the `emailSender.send(...)` call in a
try/catch on `EmailDeliveryException`, logging a warning and swallowing it — identical
to `RegistrationService.sendVerificationEmail`'s existing precedent.

#### 4. Wire the trigger into `generateBriefing`

**File**: `src/main/java/pl/tul/deltabrief/briefing/application/BriefingService.java`

**Intent**: Trigger the email from the one place every generation path already
converges on, so manual, onboarding, and scheduled generations all behave identically
with zero changes needed to `BriefingController` or `ScheduledBriefingRunner`.

**Contract**: Inject `BriefingEmailNotifier`. Immediately after
`topicRepository.recordSuccessfulGeneration(topicId, saved.generatedAt())`, if
`topic.emailEnabled()` (the already-loaded `TopicSummary` from the top of the method),
call `briefingEmailNotifier.sendBriefingEmail(userId, topic.name(), saved)`. No change
to `generateBriefing`'s signature or its callers.

#### 5. Stale comment fix

**File**: `src/main/java/pl/tul/deltabrief/config/AsyncConfig.java`

**Intent**: `AsyncConfig`'s class Javadoc currently says *"this app has no other async
work today"* — no longer true once this phase lands.

**Contract**: Update the comment to note the executor now backs two async email paths
(verification resend, briefing delivery) rather than just one.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --tests "*CitationRendererTests*"` passes — a new unit test covering
  the extracted renumbering logic directly (citation renumbering, out-of-range citation
  handling, empty-citation case), mirroring the assertions `BriefingControllerTests`
  already makes through `toView`.
- `./gradlew test --tests "*BriefingControllerTests*"` passes unchanged — confirms the
  extraction didn't alter `toView`'s observable behavior.
- `./gradlew test --tests "*UserRepositoryAdapterTests*"` passes, including a new case
  for `findEmailById` (found, and not-found-returns-empty).
- `./gradlew test --tests "*BriefingServiceTests*"` passes, including new cases:
  generating a briefing for an opted-in topic sends an email (asserted via
  `FakeEmailSender`) containing the topic name and recognizable briefing content;
  generating for an opted-out topic sends none; the onboarding briefing is emailed
  identically to a delta briefing (no special-casing by type).
- `./gradlew test --tests "*ScheduledBriefingRunnerTests*"` passes, including a new case
  confirming the scheduled path also sends the email for an opted-in due topic — proving
  the shared hook point actually covers both triggers, not just the manual one.
- `./gradlew test` passes.

#### Manual Verification:

- With a real (or locally configured) Resend API key, create a topic (opted in by
  default), generate a briefing, and confirm the email arrives with the briefing's full
  content and working link back to the app.
- Uncheck the opt-in on an existing topic, generate again, and confirm no email arrives
  for that generation.
- Trigger a scheduled generation (seed a past-due `next_due_at` as in prior scheduling
  verification) for an opted-in topic and confirm it also results in an email.

**Implementation Note**: After completing this phase and all automated verification
passes, pause here for manual confirmation from the human.

---

## Testing Strategy

### Unit Tests:

- `CitationRenderer` — renumbering, out-of-range citation handling, empty-citation case
  (moved/mirrored from the coverage `BriefingControllerTests` already established
  through `toView`).
- `Topic.applySchedule` — the new `emailEnabled` parameter is set and returned correctly.

### Integration Tests:

- `BriefingServiceTests` — opted-in generation sends an email with real content; opted-
  out generation sends none; onboarding and delta briefings are treated identically.
- `ScheduledBriefingRunnerTests` — the scheduled path also sends the email for a due,
  opted-in topic.
- `UserRepositoryAdapterTests` — `findEmailById` found/not-found.
- `TopicServiceTests`/`TopicRepositoryAdapterTests` — the opt-in flag persists and is
  readable via the cross-module summary projection.

### Manual Testing Steps:

1. Create a topic — confirm the opt-in checkbox is checked by default with its
   "(recommended)" label, and generating a briefing for it sends an email with the
   briefing's full content.
2. Uncheck the opt-in on the edit-schedule form, generate again, and confirm no email
   arrives for that generation.
3. Re-check it and confirm emails resume.
4. Seed a past-due scheduled generation for an opted-in topic and confirm the scheduled
   trigger also results in an email, not just the manual one.

## Migration Notes

`V14` backfills every existing topic to `email_enabled = true` — a deliberate, explicit
default, following V13's own precedent of choosing a real product default over a
conservative placeholder for existing rows.

## References

- Prior related work: `context/archive/2026-09-14-scheduled-briefing-generation/plan.md`
  (established the `frequency`/`preferredHour` migration-and-mutator pattern this plan
  follows) and `context/archive/2026-09-13-first-onboarding-and-delta-briefing/plan.md`
  (established the citation-numbering logic this plan extracts and reuses).
- Roadmap: `context/foundation/roadmap.md` — S-06.

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Per-topic email opt-in

#### Automated

- [x] 1.1 `TopicTests` passes including the new `applySchedule` case — cf79bc3
- [x] 1.2 `TopicServiceTests` passes including new `emailEnabled` cases — cf79bc3
- [x] 1.3 `TopicRepositoryAdapterTests` passes including the new projection case — cf79bc3
- [x] 1.4 `./gradlew build` succeeds (migration applies cleanly, including backfill) — cf79bc3

#### Manual

- [x] 1.5 New topic's opt-in checkbox defaults to checked with "(recommended)" label — cf79bc3
- [x] 1.6 Unchecking and saving persists the unchecked state — cf79bc3
- [x] 1.7 Re-checking and saving persists the checked state — cf79bc3

### Phase 2: Send the briefing by email when opted in

#### Automated

- [x] 2.1 `CitationRendererTests` passes — 7fed6e8
- [x] 2.2 `BriefingControllerTests` passes unchanged — 7fed6e8
- [x] 2.3 `UserRepositoryAdapterTests` passes including the new `findEmailById` cases — 7fed6e8
- [x] 2.4 `BriefingServiceTests` passes including opted-in/opted-out/onboarding cases — 7fed6e8
- [x] 2.5 `ScheduledBriefingRunnerTests` passes including the scheduled-path email case — 7fed6e8
- [x] 2.6 `./gradlew test` passes — 7fed6e8

#### Manual

- [x] 2.7 Opted-in topic's generated briefing arrives by email with full content and a working link — 7fed6e8
- [x] 2.8 Opted-out topic's generation sends no email — 7fed6e8
- [x] 2.9 A scheduled (not just manual) generation for an opted-in topic also sends an email — 7fed6e8
