---
starter_id: spring
package_manager: gradle
project_name: delta-brief
hints:
  language_family: java
  team_size: solo
  deployment_target: render
  ci_provider: github-actions
  ci_default_flow: auto-deploy-on-merge
  bootstrapper_confidence: verified
  path_taken: standard
  quality_override: false
  self_check_answers: null
  has_auth: true
  has_payments: false
  has_realtime: false
  has_ai: true
  has_background_jobs: true
---

## Why this stack

Solo developer shipping a news-briefing web app in 5 weeks of after-hours work with auth, AI-powered generation, and scheduled background jobs. Spring Boot is the recommended default for (web-app, java) and clears all four agent-friendly criteria — typed by Java's type system, convention-based via autoconfiguration and opinionated project layout, popular within Java training data, and well-documented with versioned reference manuals. Verified bootstrapper confidence means scaffolding will be smooth. Auth maps to Spring Security, AI/LLM briefing generation integrates via Spring AI or a direct HTTP client, and scheduled briefing jobs run on Spring's @Scheduled task execution. GitHub Actions with auto-deploy-on-merge is the CI shape. (The deployment platform was researched separately — see the decisions below and `context/foundation/infrastructure.md`.)

## Architecture decisions (settled 2026-09-09)

Decisions made after stack selection. The deployment platform is recorded in full in `context/foundation/infrastructure.md`.

- **Deployment:** Render (Standard, 2 GB, always-on) — chosen over Railway / Fly.io / GCP Cloud Run for MVP simplicity and mature agent tooling under a tight timeline. `deployment_target` above was updated from `fly` to `render`.
- **Database:** Supabase (managed Postgres) over JDBC — used as the **database only**, not Supabase Auth. Free tier for testing (persistent, pauses when idle); Pro once real briefing history matters.
- **Frontend:** **Server-rendered** (Spring MVC + Thymeleaf), enhanced with **HTMX** for the one dynamic area — live feedback while a briefing generates. Responsive CSS (e.g. Pico.css) gives mobile-browser access. No SPA; a native mobile app is out of scope for the MVP (the same Spring app can expose a REST API later if one is ever needed).
- **Auth:** **Spring Security, session-based** form login (BCrypt passwords, user table in Supabase Postgres). The app does **not** emit JWTs and is **not** an OAuth2 authorization server. Optional social login would make the app an OAuth2 *client* of the provider (e.g. Google), still session-backed.
- **API surface:** **No public JSON/REST API** for the MVP — controllers return HTML pages and HTMX fragments, all same-origin behind the session cookie (CSRF protection on; HTMX requests carry the CSRF token). The only non-HTML endpoint is `/actuator/health` (Render health check). `@RestController` JSON endpoints get added later only if a native app / SPA / third-party integration arrives.
- **Budget:** ≤ $25/month during testing, free tiers preferred (see `infrastructure.md`).
- **Codebase organization:** **DDD modular monolith** under `pl.tul.deltabrief` — one package per bounded context (`auth`, `topic`, `briefing` as the core domain, `delivery`, `feedback`, plus a `shared` package for cross-cutting code), each internally layered `domain` → `application` → `adapter.in.web` / `adapter.out.<concern>` (hexagonal/ports-and-adapters naming). Modules reference each other by ID only, never by importing another module's domain aggregate. Full convention recorded in `@AGENTS.md`.
- **Git workflow:** **Trunk-based**, not git-flow — `main` is always deployable (merges auto-deploy to Render) with short-lived `feature/*` branches; no `develop` branch. Chosen over git-flow because a solo developer has no second developer's in-flight work to isolate and no release train to coordinate — `develop` would add ceremony with no offsetting benefit. Repo: `github.com/ninjarlz/delta-brief` (public), commit author scoped to the `ninjarlz` GitHub account (kept separate from this machine's corporate git identity).
