# Wire Database Connectivity — Plan Brief

> Full plan: `context/changes/wire-database-connectivity/plan.md`

## What & Why

DeltaBrief has zero database code today. This change connects the app to a real PostgreSQL database — Supabase in production, a local Docker Postgres for development — with Flyway migration tooling in place, so S-01 (registration/login) and every later slice can add real schema incrementally. This is Foundation F-01 on the roadmap: the enabler, not the schema.

## Starting Point

No JDBC/JPA/Flyway dependency exists in `build.gradle`; no datasource config exists anywhere; no Supabase project has been created. The only test today (`DeltaBriefApplicationTests`) doesn't touch persistence — it just checks Spring boots. The deploy pipeline itself (Dockerfile, render.yaml, GitHub Actions) is already live and proven at `https://delta-brief.onrender.com`.

## Desired End State

The app boots successfully against a real Postgres in both environments — locally via Docker, on Render via Supabase — with Flyway managing an (initially empty) schema and JPA ready for the first real entity. `./gradlew test` proves the whole stack automatically against a genuine, ephemeral Postgres container, not a mock.

## Key Decisions Made

| Decision | Choice | Why (1 sentence) | Source |
| --- | --- | --- | --- |
| Migration tool | Flyway | Simple SQL migrations with first-class Spring Boot support; avoids Hibernate auto-DDL's risk of silently altering a real, shared database | Plan |
| Data-access technology | Spring Data JPA | Standard Spring pattern, least boilerplate, most training-data support for future agent-driven work | Plan |
| Primary-key strategy | `BIGSERIAL`/`IDENTITY` | At this project's actual scale (dozens–hundred users, low QPS per the PRD), UUID's real index-fragmentation cost buys convention benefits the project doesn't need yet | Plan |
| Local dev database | Separate Docker Compose Postgres | Isolates local experimentation from the real (eventually user-facing) Supabase data; works offline | Plan |
| Environment separation | Single Supabase project | Local dev is already isolated via Docker — a second Supabase project for dev/prod split would be pure overhead with no real users yet | Plan |
| CI database verification | Testcontainers | Identical behavior locally and in CI, zero `ci-cd.yml` changes needed (Docker's already on GitHub Actions runners), the standard Spring Boot 3+/4 pattern | Plan |
| Local credentials | Env-var-overridable defaults in `application.properties` | Docker Postgres dev credentials aren't secrets, so this extends the project's existing `${PORT:8080}` pattern instead of adding a new Spring profile | Plan |

## Scope

**In scope:**
- Flyway + Spring Data JPA + Postgres driver + Testcontainers dependencies
- `docker-compose.yml` for local Postgres 17
- Datasource configuration (env-var overridable, HikariCP pool capped per `infrastructure.md`)
- Upgrading the existing context-loads test to a real Testcontainers-backed proof
- Creating the real Supabase project and wiring its credentials into Render

**Out of scope:**
- Any `@Entity`/repository/domain table (arrives with S-01)
- UUID/extension-based key generation infrastructure
- A second Supabase project for staging/dev isolation
- Any `ci-cd.yml` workflow changes

## Architecture / Approach

`application.properties` gains env-var-overridable datasource settings whose defaults point at the local Docker Postgres — the same pattern already used for `server.port`. Render's real `SPRING_DATASOURCE_*` env vars transparently override these when deployed; no Spring profile is needed. Verification happens in two layers: a Testcontainers-backed test proves the mechanism works in isolation (locally and in CI), then a live `curl` against the deployed `/actuator/health` (which auto-gains a `db` indicator once JPA is present) proves the real Supabase connection works too.

## Phases at a Glance

| Phase | What it delivers | Key risk |
| --- | --- | --- |
| 1. Wire local dependencies & config | App boots locally against real Postgres | Spring Boot 4 requires a *new* `spring-boot-starter-flyway` — the old `flyway-core`-only approach silently no-ops |
| 2. Prove it with a real, ephemeral Postgres | `./gradlew test` genuinely verifies datasource+Flyway+JPA | `@ServiceConnection` may need an explicit `name` on Boot 4 (known, reported quirk) |
| 3. Wire the deployed environment | Render app connects to real Supabase | Supavisor's pooled username is `postgres.<project-ref>`, not plain `postgres` — an easy, silent misconfiguration |

**Prerequisites:** None beyond what's already live (the deploy pipeline). Docker must be available locally for `docker compose up` and for Testcontainers to run tests.
**Estimated effort:** Not applicable — the roadmap this plan implements deliberately carries no time/effort estimates.

## Open Risks & Assumptions

- Testcontainers' exact version is assumed to be managed transitively via Spring Boot's platform BOM (already imported through `io.spring.dependency-management`) — if that assumption is wrong, an explicit version property may be needed during implementation.
- The `@ServiceConnection` Boot 4 naming quirk is a known, currently-open upstream issue — Phase 2 names the workaround (`name = "postgres"`) but the exact failure mode hasn't been reproduced locally yet.

## Success Criteria (Summary)

- `./gradlew test` passes against a real, ephemeral Postgres container — not a no-op boot check.
- The deployed app at `https://delta-brief.onrender.com/actuator/health` reports `db: UP` against the real Supabase connection.
- Zero domain schema exists yet — S-01 is unblocked to add the first real migration next.
