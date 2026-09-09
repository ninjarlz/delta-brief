---
project: DeltaBrief
researched_at: 2026-09-09
recommended_platform: Render
runner_up: Railway
context_type: mvp
tech_stack:
  language: Java 21
  framework: Spring Boot 4
  runtime: JVM
data_layer: Supabase (managed Postgres)
ci: GitHub Actions
test_budget_usd_month: 25
budget_policy: prefer-free-tiers
---

## Recommendation

**Deploy the Spring Boot service on Render, with Supabase as the managed Postgres and GitHub Actions for CI/CD.**

The app is a persistent JVM server with in-process `@Scheduled` briefing jobs, which rules out edge/serverless-only hosts (Cloudflare Workers, Vercel, Netlify — no always-on JVM). Among the viable container platforms, Render was chosen for its always-on paid instances, native Cron Jobs as a scheduling escape hatch, GA MCP server plus a Claude Code plugin, and markdown/`llms.txt` docs. Because the developer opted for an external database, the choice of Supabase gives managed **daily backups** (7-day retention) — with PITR available as a paid add-on if ever needed — plus pgvector for future embedding-based briefing work; a stronger default data-safety posture than co-located PaaS databases (Railway's snapshot-only, Fly's still-rolling-out managed Postgres). Render's own managed Postgres was weighed as a one-platform, lower-cost alternative (~$6/mo Basic vs Supabase Pro's $25), but Supabase was kept for its genuinely-free, persistent test database — Render's free Postgres is deleted at 30 days with no backups — and for host-independent portability. GitHub Actions was already the planned CI (`auto-deploy-on-merge`). Cloudflare — the course's edge recommendation — was considered but **deferred**: it is a pure DNS/proxy layer with marginal value for a single-region, mostly-dynamic MVP, and can be added in front of Render later with no code changes if WAF or rate-limiting is wanted.

## Platform Comparison

Hard filter: the app requires an always-on JVM process, which drops **Cloudflare Workers** (JS/Wasm edge runtime, no JVM), **Vercel**, and **Netlify** (serverless functions, no persistent JVM). Cloudflare *Containers* (GA Apr 2026) can run Spring Boot but are designed for bursty, scale-to-zero workloads — "not a drop-in replacement for an always-on PaaS" — so they fight `@Scheduled` and cost more when kept warm. The four researched container platforms:

| Platform | CLI-first | Managed infra | Agent-readable docs | Stable deploy API | MCP / integration | Score |
|---|---|---|---|---|---|---|
| **Render** | Pass | Pass | Pass (`llms.txt`) | Pass | Pass | 5 / 5 |
| **Railway** | Pass | Pass | Pass (`llms.txt`) | Pass | Pass | 5 / 5 |
| **Fly.io** | Pass | Pass | Partial (no `llms.txt`) | Pass | Pass | 4½ / 5 |
| **Google Cloud Run** | Pass | Pass | Pass | Pass | Partial (MCP early) | 4½ / 5 |

- **Render** — Docker-only for Java (you maintain a multi-stage Gradle → JRE image). Paid instances are always-on (`@Scheduled` works); free web services spin down after 15 min and would silently break scheduling. Native **Cron Jobs** (GA) provide an alternative to in-process scheduling. Official MCP server (GA Aug 2025, 20+ tools) plus a Claude Code plugin; `.md` / `llms.txt` docs; GitHub push auto-deploy. Pricing: Free (512 MB, spins down), Starter $7 (512 MB — tight for this stack), **Standard $25 (2 GB — realistic floor)**.
- **Railway** — Railpack auto-detects Gradle/Java 21 (no Dockerfile). Always-on by default (best `@Scheduled` fit), `llms.txt` docs, MCP server, cheapest ($5 Hobby). Runner-up; its co-located-Postgres edge is neutralized by the external-DB choice, and it carries cost-creep / no-PITR / lock-in caveats.
- **Fly.io** — strongest raw JVM story and native `fly mcp server --claude`, but auto-stop **silently kills `@Scheduled`** unless disabled (`min_machines_running = 1`), managed Postgres is still rolling out, and DX is lower-level (`fly.toml`).
- **Google Cloud Run** — matches the developer's GCP familiarity, but scale-to-zero + CPU throttling structurally fights in-process `@Scheduled` (needs `min-instances=1 --no-cpu-throttling` ~$10–12/mo or a re-architecture to Cloud Scheduler→HTTP), plus JVM cold starts (~12 s) and Cloud SQL Java-Connector complexity. Most capable, least MVP-simple.

### Shortlisted Platforms

#### 1. Render (Recommended)

Always-on paid instances run the JVM and its `@Scheduled` jobs without surprises; native Cron Jobs offer a fallback if scheduling ever needs process isolation. Best-in-class agent tooling (GA MCP + Claude Code plugin, `llms.txt` docs, `render.yaml` IaC). Docker-only for Java is the main friction, and 512 MB tiers are too small — Standard (2 GB) is the real starting point.

#### 2. Railway

Technically the smoothest DX (Gradle auto-detect, always-on default, cheapest, MCP). Runner-up because the developer preferred Render's Cron Jobs + Claude Code integration, and because the external-DB decision removes Railway's co-located-Postgres advantage while its cost-creep, snapshot-only backups (no PITR), and vendor lock-in remain.

#### 3. Fly.io

Excellent JVM fit and native MCP, but the auto-stop foot-gun for `@Scheduled` and immature managed Postgres put it third for this always-on, scheduled-jobs app.

## Anti-Bias Cross-Check: Render

### Devil's Advocate — Weaknesses

1. **Docker-only for Java.** No native Java runtime — you write and maintain a multi-stage Dockerfile (Gradle build → JRE runtime). More surface to get wrong (base image, heap flags, layer caching) and slower builds than an auto-detect platform.
2. **512 MB is too small for this stack.** Spring Boot 4 + Spring Security + Spring AI plus JVM metaspace routinely needs 700 MB–1 GB. Free and Starter ($7) both cap at 512 MB, so the real floor is Standard ($25/2 GB) — the cheap sticker doesn't apply.
3. **Scheduling is a fork with a trap.** In-process `@Scheduled` needs a paid always-on instance (fine); the free tier spins down and silently stops the scheduler. Render Cron Jobs run in a *separate* short-lived container that can't share the Spring context or Hikari pool — it re-bootstraps a JVM each run (cold start, duplicate Spring AI init) or requires an internal HTTP endpoint.
4. **Rollback is not fully CLI-native.** The CLI handles deploys and logs, but rollback is via dashboard/API — a small gap for an agent-driven ops loop.
5. **Three control planes.** Compute (Render) + DB (Supabase) + CI (GitHub) means three dashboards and secret stores to keep in sync — fewer than a hyperscaler, but still more than an all-in-one PaaS.

### Pre-Mortem — How This Could Fail

Six months in, DeltaBrief on Render was fine technically but bumpier than planned. It started on the $7 Starter: Spring Boot 4 with Security and Spring AI OOM-ed on 512 MB, so after a week of heap-flag tuning the team moved to the $25 Standard — quietly quadrupling the compute line. The scheduling decision bit next: to trim cost they briefly ran the web service on the free tier, where spin-down meant daily briefings silently never fired, and a few early users got nothing and churned before anyone read the (empty) logs. They kept `@Scheduled` on an always-on instance after that. The hand-rolled Dockerfile drifted — a floating base-image tag changed the JRE and broke a build mid-sprint. Supabase was the bright spot: daily backups and pgvector earned their keep. But coordinating three control planes meant a rotated LLM key had to be updated in two places; once it wasn't, causing a confusing prod outage. None fatal; all foreseeable.

### Unknown Unknowns

- **The $7 tier is a trap for this stack.** Standard ($25/2 GB) is the real floor for Spring Boot 4 + Security + AI. Budget it from day one.
- **Free web services spin down (kills `@Scheduled`) and free Postgres would expire** — treat "free" as demo-only. Run the app on a paid always-on instance; use Supabase for the DB.
- **Render Cron Jobs run in a separate container** — they can't see your Spring context or connection pool. For MVP, keep `@Scheduled` in-process on the always-on instance; reach for Cron Jobs only if you need guaranteed single-run isolation.
- **You maintain a Dockerfile.** Pin the base image (e.g. `eclipse-temurin:21-jre`) and build via the committed `./gradlew` wrapper; a floating tag will eventually break a build.
- **Secret sprawl and connection limits.** `spring.ai.openai.api-key` and the Supabase credentials live in Render env vars that the Render MCP/CLI can enumerate — scope tokens. For the DB, use Supabase's **Supavisor pooler** (transaction mode, port 6543), not the direct connection, and cap HikariCP `maximum-pool-size`, or a JVM pool will exhaust connections.

## Operational Story

- **Preview deploys**: Render preview environments from `render.yaml` (`previewsEnabled`) spin up an ephemeral service + URL per PR; fork PRs are restricted by default. Point previews at a Supabase branch or a shared dev database rather than production.
- **Secrets**: env vars set per-service on Render (dashboard / CLI / `render.yaml` `envVars`, or an env group) — `SPRING_DATASOURCE_URL`, `SPRING_DATASOURCE_USERNAME`, `SPRING_DATASOURCE_PASSWORD`, `SPRING_AI_OPENAI_API_KEY`; GitHub Actions secrets hold the Render deploy hook/API key. Rotate in each plane; nothing is committed.
- **Rollback**: Render dashboard/API "Rollback" swaps back to a prior deploy's image in minutes, or redeploy a previous commit. Caveat: Flyway/Liquibase migrations are forward-only — a code rollback does not revert schema; use Supabase PITR to restore data if a migration corrupted it.
- **Approval**: production deploy gated by the GitHub Actions flow (`auto-deploy-on-merge` to `main`) or manual approval; rotating the LLM key or DB credential, and any Supabase restore/drop, require a human; an agent may read logs/status unattended.
- **Logs**: `render logs` (live tail) / `render logs --resources <svc>` / dashboard / Render MCP tools; Spring Boot logs to stdout by default. Database logs live in the Supabase dashboard.

## Risk Register

| Risk | Source | Likelihood | Impact | Mitigation |
|---|---|---|---|---|
| 512 MB OOM on Free/Starter for Spring Boot 4 + Security + AI | Research finding / Devil's advocate | H | M | Start on Standard (2 GB); set `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`. |
| Free-tier spin-down silently kills `@Scheduled` | Devil's advocate / Unknown unknowns | M | H | Run the web service on a paid always-on instance; never free for prod. |
| Render Cron Jobs can't share Spring context/pool | Pre-mortem / Unknown unknowns | M | M | Keep `@Scheduled` in-process on the always-on instance for MVP; use Cron Jobs only for isolated single-run tasks. |
| Dockerfile drift (no native Java build) | Devil's advocate | M | M | Pin base image; build via committed `./gradlew` wrapper; build the image in CI. |
| Secret sprawl across Render / Supabase / GitHub | Pre-mortem | M | M | Centralize via Render env group + GitHub secrets; document rotation; scope tokens. |
| Supabase connection exhaustion from JVM pool | Unknown unknowns / Research | M | M | Use Supavisor pooler (transaction mode, 6543); cap HikariCP `maximum-pool-size`. |
| Cost creep $7 → $25+/mo | Devil's advocate | M | L | Budget Standard from day one; watch Render metrics. |
| DB migrations forward-only on code rollback | Operational finding | L | M | Design backward-compatible migrations; rely on Supabase daily backups (or the PITR add-on) for data restore. |
| Rollback not fully CLI-native | Devil's advocate | L | L | Use Render dashboard/API rollback; document the runbook. |

## Cost Estimate

**Budget guardrail: keep total spend ≤ $25/month during testing, preferring free tiers wherever possible.** The LLM API is the only strictly metered cost — cap it explicitly (an OpenAI usage limit) so it cannot breach the budget.

### Testing / dev (target ≤ $25/mo)

| Service | Choice | Cost |
|---|---|---|
| Render | Free (512 MB, spins down) for endpoint dev; **Starter $7** (always-on) only when validating `@Scheduled` | $0–7 |
| Supabase | Free (500 MB; kept awake by the scheduler once running) | $0 |
| GitHub Actions | Free tier (2,000 min/mo private) | $0 |
| OpenAI (Spring AI) | Small/`mini` model + a hard usage cap in the OpenAI dashboard | ≤ ~$10 |
| **Total** | | **~$0–17/mo** |

Two ways to run testing within budget:
- **All-free (~$0–10):** Render Free + Supabase Free + a small-model LLM. Caveat: Render spin-down means `@Scheduled` won't fire reliably — fine for building/exercising endpoints, not for validating the scheduled-briefing loop.
- **Scheduler-faithful (~$17):** swap Render to **Starter $7** (always-on) so `@Scheduled` runs; keep everything else free. Tune `-XX:MaxRAMPercentage` — 512 MB is tight for Spring Boot 4 + Security + AI, so watch for OOM under load.

**Enforce the cap:** set a monthly usage limit + billing alert on the OpenAI account (e.g. $10–15). That is the one line item that can run away; everything else is fixed or free.

### Production (for reference — beyond the test budget)

| Service | Choice | Cost |
|---|---|---|
| Render | Standard (2 GB, always-on) | $25 |
| Supabase | Free early → Pro when real briefing history matters | $0 → $25 |
| GitHub Actions | Free | $0 |
| OpenAI | model- and volume-dependent | ~$2–40 |
| **Total** | | **~$25–50/mo + LLM** |

PITR ($100/mo add-on) is **not** budgeted — Supabase Pro daily backups suffice for MVP. LLM cost is dominated by model choice, not user count at MVP scale: a small model keeps it near $2/mo; a flagship model at ~600 briefings/mo lands ~$25–40.

## Getting Started

Version-accurate for Spring Boot 4 / Java 21 / Gradle on Render + Supabase:

1. **Add Actuator for health checks** — add `org.springframework.boot:spring-boot-starter-actuator` to `build.gradle`; Render health-checks `/actuator/health`.
2. **Add a multi-stage Dockerfile** — build stage on a JDK 21 base running `./gradlew bootJar` via the committed wrapper; runtime stage on `eclipse-temurin:21-jre` running the boot jar. (Full Dockerfile authoring is out of scope for this decision doc.)
3. **Create the Supabase project** — copy the Postgres connection string using the **Supavisor pooler** URI (transaction mode, port 6543); enable the `pgvector` extension if briefing generation will use embeddings.
4. **Create the Render Web Service (Docker)** — via `render.yaml` Blueprint or dashboard; choose **Standard (2 GB)**; set env vars (`SPRING_DATASOURCE_URL/USERNAME/PASSWORD`, `SPRING_AI_OPENAI_API_KEY`, `JAVA_TOOL_OPTIONS=-XX:MaxRAMPercentage=75`); set health-check path `/actuator/health`.
5. **Wire GitHub Actions** — build/test with `./gradlew build` on push; on merge to `main`, trigger the Render deploy hook (or `render deploys create`) for `auto-deploy-on-merge`. Store the deploy hook/API key as a GitHub Actions secret.
Keep `@Scheduled` in-process on the always-on Standard instance for the MVP; revisit Render Cron Jobs only if a job needs guaranteed single-run isolation.

> **Cloudflare was intentionally left out of the MVP stack.** It is a pure DNS/proxy layer that can be added in front of Render later with no code changes — worth revisiting only if you want a WAF or rate-limiting on the auth and generation routes.

## Out of Scope

The following were not evaluated in this research:
- Docker image configuration (the Dockerfile shape is sketched in Getting Started but not authored here)
- CI/CD pipeline configuration (GitHub Actions workflow files)
- Production-scale architecture (multi-region, HA, DR)
- Supabase Auth integration vs. Spring Security (noted as a decision, not resolved here)
