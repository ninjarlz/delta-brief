# User Registration and Login — Plan Brief

> Full plan: `context/changes/user-registration-and-login/plan.md`
> Frame brief: `context/changes/user-registration-and-login/frame.md`
> Research: `context/changes/user-registration-and-login/research.md`

## What & Why

Build DeltaBrief's first bounded-context module, `auth`: email/password registration with a minimal email-verification link, session-based login/logout, and the `users` table. This is roadmap slice S-01 — every downstream slice (topics, briefings) needs per-user data ownership from day one, per the PRD's access-control model.

## Starting Point

The codebase has zero auth code: `pl.tul.deltabrief` has only `DeltaBriefApplication`, a bare `SecurityConfig` (permits `/` and `/actuator/health`, everything else 403s), and a temporary placeholder controller. No `users` table, no Flyway migrations, no form-handling template convention exist yet. The architecture itself (session-based Spring Security, BCrypt hashing, JPA-backed `UserDetailsService`) was already settled before this plan, per `tech-stack.md` and `research.md`.

## Desired End State

A visitor can register with email + password, gets a verification-link email, can log in and out via a session-backed form. Once Phase 4 lands, the deployed app sends real verification emails to real users from a verified domain — not just the developer's own test address.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| OAuth in this slice? | No — deferred to `S-08` | Confirmed additive/cost-free to add later; not required by FR-001. | Frame |
| Registration fields | Email + password + confirm only | Exactly what FR-001 requires, nothing more. | Plan |
| Email verification | Minimal link flow, via Resend SMTP | User chose to build it now rather than defer to S-06. | Plan |
| Session duration | Default Spring timeout | User chose the conservative default over extending it. | Plan |
| Duplicate-email prevention | DB `UNIQUE` constraint + app-layer check | Closes the race window while keeping a friendly UX message. | Plan |
| Email provider | Resend (SMTP) | Only provider with a genuinely free tier in 2026 (SendGrid killed its free tier); reusable by S-06 later. | Plan |
| Login gated on verification? | No | Verification tracks future email-sending eligibility, not access control. | Plan |
| Primary key strategy | `BIGSERIAL`/`IDENTITY` | Already decided during F-01; not revisited. | Research |

## Scope

**In scope:** registration (email/password), password hashing, email verification link (dev-scoped until Phase 4), login/logout, the `users` table, a reusable `EmailSender` abstraction.

**Out of scope:** OAuth (`S-08`), password reset, resend-verification-email UI, gating login on verification, remember-me/persistent sessions, display name or other profile fields, rate-limiting.

## Architecture / Approach

A new `auth` DDD module (`domain` → `application` → `adapter.in.web` / `adapter.out.persistence` / `adapter.out.security`), plus a new `shared` package holding a generic `EmailSender` port (not auth-specific — S-06 reuses it for briefing delivery). `SecurityConfig` (in the existing `config` package) gets form login/logout wiring. Phases build bottom-up: persistence skeleton → registration/verification → login/logout → deployed domain, mirroring F-01's proven phase shape.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Data model & skeleton | `users` table, domain/persistence layers, provable via a repository test | Low — follows F-01's already-proven migration pattern |
| 2. Registration & verification (dev-scoped) | Registration flow + Resend-backed verification email | Resend's test address only reaches the developer's own inbox — expected, not a bug |
| 3. Login & logout | `SecurityConfig` rewrite, full flow proven end-to-end in CI | Must reuse `TestcontainersDatasourceConfig`'s exact context shape (known single-context port constraint) |
| 4. Deployed environment | Real domain verified with Resend, production env vars on Render | Requires the user to actually own/verify a domain — manual, browser-only |

**Prerequisites:** F-01 (done). A free Resend account (Phase 2); a domain to verify (Phase 4).
**Estimated effort:** ~4 phases, similar scope to F-01's 3-phase implementation plus the added email-verification slice.

## Open Risks & Assumptions

- Assumes the user will acquire/verify a domain with Resend before Phase 4 — if that stalls, Phases 1-3 are still fully functional for local/dev use, just not for real end-user email delivery.
- The `TestcontainersDatasourceConfig` single-Spring-context assumption (a known, previously-flagged gap from F-01's review) must be respected by construction in Phase 3's new test, not worked around.

## Success Criteria (Summary)

- A user can register, receive a verification email, verify, log in, and log out — proven automatically via a real-Postgres integration test and manually through the running app.
- The deployed app (post-Phase 4) delivers verification emails to real, non-developer email addresses.
