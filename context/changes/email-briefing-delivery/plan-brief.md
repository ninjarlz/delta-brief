# Email Briefing Delivery — Plan Brief

> Full plan: `context/changes/email-briefing-delivery/plan.md`

## What & Why

Let a user opt in per topic to receive newly generated briefings by email (FR-012).
Every generation trigger — manual "Generate now," the scheduled poll job, onboarding or
delta — should deliver the same email uniformly to an opted-in topic's owner, without
requiring them to open the app.

## Starting Point

The reusable transport already exists: `EmailSender`/`ResendSmtpEmailSender` are wired
to Resend's SMTP relay and proven via `RegistrationService`'s account-verification
email, but that's their only caller today — no briefing content has ever been emailed.
Two real gaps stood between that infrastructure and this feature: nothing could resolve
a user's email from a `UserId`, and the citation-renumbering logic that makes a
briefing's sources readable lived privately inside the web controller, unreachable from
anywhere else.

## Desired End State

Every topic's create and edit forms show an "email me new briefings" checkbox (checked
by default, labeled recommended). When a briefing is generated for an opted-in topic, by
any trigger, its owner receives a plain-text email with the full briefing content and a
link back to the app, shortly after — sent asynchronously so it never adds latency to
the generation itself.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) |
| --- | --- | --- |
| Opt-in granularity | Per-topic | Matches the existing frequency/preferredHour pattern — topics already carry independent schedule settings. |
| Default state | Opted in, labeled "(recommended)" | Explicit user direction during planning, consistent with the product's "delta newsletter" framing. |
| Existing topics | Backfilled to opted in | Mirrors V13's own precedent of choosing a real default over a conservative placeholder for existing rows. |
| Format | Plain text | The entire email stack (`SimpleMailMessage`) is plain-text only today; HTML would mean new transport-layer work with no other precedent in the app. |
| Content | Full briefing inline | Matches the "no need to open the app" value proposition; content is already assembled as plain strings, no condensing design work needed. |
| Delivery timing | Async via the existing `emailTaskExecutor` | Avoids adding SMTP latency to the manual HTTP response or the scheduler's bounded per-topic dispatch. |
| Send failure | Log and swallow | Mirrors `sendVerificationEmail`'s existing precedent exactly — email failure never fails the underlying operation. |
| Onboarding briefings | Emailed identically to delta briefings | The shared hook point is already briefing-type-agnostic; no product reason to branch. |
| Opt-out | Uncheck the same checkbox | Consistent with how frequency itself is already changed; no new unsubscribe-token flow for v1. |

## Scope

**In scope:**
- `topics.email_enabled` column, `Topic` domain field, threaded through create/edit forms
- A new `UserRepository.findEmailById` (email-only projection, never the full `User` aggregate)
- A shared `CitationRenderer` extracted from `BriefingController` into `briefing.domain`
- A new `BriefingEmailNotifier` bean, async, wired into `BriefingService.generateBriefing`'s shared success path

**Out of scope:**
- HTML/styled email
- Account-wide (rather than per-topic) opt-in
- Tokenized one-click unsubscribe links
- Retry-on-failure for a dropped send
- Condensed/summarized email content

## Architecture / Approach

The opt-in flag rides the same path `frequency`/`preferredHour` already established
(migration → `Topic` field → `applySchedule` mutator → both DTOs → both templates). On
the delivery side, `BriefingService.generateBriefing` — already the single point every
generation trigger converges on — gains one conditional call to a new, separately-invoked
`BriefingEmailNotifier` bean (kept separate specifically so its `@Async` annotation
actually takes effect; a self-invoked async method would silently run synchronously).
That notifier resolves the recipient via the new email-only `UserRepository` lookup and
renders content via a citation-rendering utility extracted out of the web controller into
`briefing.domain`, so the web page and the email are provably showing the same numbered
sources.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Per-topic email opt-in | Schema, domain field, and form checkbox — no email sent yet | Getting the Pico checkbox markup pattern right (input-inside-label, unlike this form's other fields) |
| 2. Send the briefing by email | Actual delivery, wired into the shared generation hook | The `@Async` self-invocation pitfall — mitigated by a dedicated collaborator bean |

**Prerequisites:** S-03 (done) — briefing generation and content already exist. The
verification-email infrastructure (`EmailSender`, `ResendSmtpEmailSender`,
`emailTaskExecutor`) already exists from the auth module.

## Open Risks & Assumptions

- Assumes a real (or locally configured) Resend API key is available for the manual
  verification steps in Phase 2 — without one, delivery can only be confirmed via
  `FakeEmailSender` in automated tests, not a real inbox.
- Assumes extracting `BriefingController`'s citation logic into `briefing.domain`
  doesn't change any of its observable behavior — mitigated by keeping
  `BriefingControllerTests`'s existing assertions as the regression check.

## Success Criteria (Summary)

- A topic's create/edit forms show a checked-by-default, "(recommended)" opt-in checkbox.
- Every successful generation for an opted-in topic — manual or scheduled, onboarding or
  delta — results in an email with the full briefing content.
- Opting out (unchecking the box) stops future emails for that topic without any other
  workflow change.
