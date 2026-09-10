# Wire Database Connectivity Implementation Plan

## Overview

Connect DeltaBrief to a real PostgreSQL database — Supabase in the deployed Render environment, a local Docker Postgres for development — with Flyway migration tooling in place, so that S-01 (user registration and login) and every subsequent slice can add real schema incrementally. This is Foundation F-01 on the roadmap: a minimal enabler, not a schema-design exercise — no `@Entity` classes or domain tables are created here.

## Current State Analysis

The codebase has zero database code today: no JDBC/JPA/Flyway dependency in `build.gradle`, no datasource configuration in `application.properties`, no Supabase project created yet. `DeltaBriefApplicationTests` is a bare `@SpringBootTest` whose only assertion is that the Spring context loads — it doesn't touch persistence at all. `ci-cd.yml`'s `build-and-test` job has no database dependency by design (this was deliberate scope-control during the placeholder deployment). The deploy pipeline itself (Dockerfile, render.yaml, GitHub Actions build+deploy) is live and already proven end-to-end at `https://delta-brief.onrender.com`.

## Desired End State

The app boots successfully — locally against a Docker Postgres, and on Render against a real Supabase Postgres — with Flyway managing the schema (currently empty; the first real migration arrives with S-01) and Spring Data JPA ready to back repositories once entities exist. `./gradlew test` proves this automatically via a real, ephemeral Postgres container. The deployed app on Render is verified to actually connect to Supabase (not just to boot).

Verification: `./gradlew test` passes with a real Postgres-backed context-loads test (not just an in-memory/no-op boot check); the deployed app's logs show a successful Flyway/Hikari startup against Supabase; `curl` against `/actuator/health` still returns `200 UP` post-deploy.

### Key Discoveries:

- Spring Boot 4 changed Flyway auto-configuration: `flyway-core` alone (the Boot 3 way) is **not** sufficient anymore. Boot 4 requires the new `org.springframework.boot:spring-boot-starter-flyway` starter, plus `org.flywaydb:flyway-database-postgresql` (Flyway 10+ split database-specific support into separate modules). Source: Spring Boot's own [4.0 migration guide](https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide).
- `spring-boot-starter-data-jpa` and `org.postgresql:postgresql` are unrenamed in Boot 4 — safe to add as-is.
- Supabase's Supavisor transaction-mode pooler (port 6543, already the project's chosen connection mode per `infrastructure.md`) uses a **non-obvious username format**: `postgres.<PROJECT_REF>`, not plain `postgres`. Getting this wrong is a common, confusing connection-failure source.
- Supabase's default Postgres major version is currently **17** — the local Docker Compose service and the Testcontainers image should both pin `postgres:17` to avoid any dialect mismatch against the real deployed database.
- Spring Boot's `@ServiceConnection` (used for Testcontainers wiring) has a reported Boot 4 quirk: it can throw `No ConnectionDetailsFactory found` unless given an explicit `name` — e.g. `@ServiceConnection(name = "postgres")`. Confirmed as a known, currently-open Boot 4 issue.
- GitHub Actions' `ubuntu-latest` runners have Docker available by default — a Testcontainers-based test approach needs **zero changes** to `ci-cd.yml`.

## What We're NOT Doing

- No `@Entity` classes, JPA repositories, or domain tables — the first real schema (a users table) arrives with S-01, not here.
- No UUID primary-key infrastructure — the project uses `BIGSERIAL`/`IDENTITY` columns declared per-table, so no baseline migration or extension setup is needed for key generation.
- No second Supabase project for staging/dev isolation — local dev uses its own separate Docker Postgres instead, so a single Supabase project for the one deployed environment is sufficient.
- No `local` Spring profile or gitignored properties file — local Docker Postgres credentials are fixed, non-secret dev values, safely defaulted directly in the committed `application.properties` (matching the existing `${PORT:8080}` pattern), overridden by real env vars on Render.
- No changes to `ci-cd.yml` — Testcontainers needs no GitHub Actions service block.

## Implementation Approach

Extend `application.properties` with env-var-overridable datasource settings whose *defaults* point at a new local Docker Compose Postgres — mirroring the project's existing `server.port=${PORT:8080}` convention exactly, so Render's real `SPRING_DATASOURCE_*` env vars transparently override the safe local defaults with no profile machinery needed. Prove the whole stack (datasource + Flyway + JPA autoconfiguration) works by upgrading the existing `DeltaBriefApplicationTests` to run against a real, ephemeral Testcontainers Postgres instead of asserting nothing more than "Spring boots." Finish by creating the actual Supabase project and wiring its real credentials into Render — the one step that's inherently manual (browser-only, per this project's established pattern for account-level setup).

## Critical Implementation Details

**Supavisor username format.** When setting the real Render env vars in Phase 3, `SPRING_DATASOURCE_USERNAME` must be `postgres.<project-ref>` (the Supabase project reference appended after a dot), not plain `postgres` — the latter is only valid for the direct (non-pooled) connection, which this project deliberately does not use.

**`@ServiceConnection` may need an explicit name on Boot 4.** If the Testcontainers-backed test fails at startup with `No ConnectionDetailsFactory found`, add `name = "postgres"` to the `@ServiceConnection` annotation — this is a known, reported Boot 4 quirk, not a sign the container/config is wrong.

## Phase 1: Wire local dependencies and configuration

### Overview

Add the JPA/Flyway/Postgres dependencies, a local Docker Compose Postgres, and datasource configuration — enough for `./gradlew bootRun` to boot successfully against a real local database with an empty (but working) Flyway-managed schema.

### Changes Required:

#### 1. `build.gradle`

**File**: `build.gradle`

**Intent**: Add the dependencies needed for JPA-backed persistence against Postgres, migrated by Flyway.

**Contract**: Add to the `dependencies` block:
```gradle
implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
implementation 'org.springframework.boot:spring-boot-starter-flyway'
implementation 'org.flywaydb:flyway-database-postgresql'
runtimeOnly 'org.postgresql:postgresql'
testImplementation 'org.springframework.boot:spring-boot-testcontainers'
testImplementation 'org.testcontainers:junit-jupiter'
testImplementation 'org.testcontainers:postgresql'
```
No explicit Testcontainers version is needed — the project's existing `io.spring.dependency-management` plugin imports Spring Boot's platform BOM, which manages compatible Testcontainers versions. Verify this resolves cleanly; if not, an explicit `testcontainers.version` property may be needed (fallback, not the expected path).

#### 2. `docker-compose.yml` (new, repo root)

**File**: `docker-compose.yml`

**Intent**: Give local development a real Postgres to run against, matching Supabase's major version.

**Contract**: One `postgres:17` service, fixed non-secret dev credentials (database/user/password all `deltabrief`), port `5432` published to the host. No volumes/persistence needed beyond the container's own lifecycle — this is disposable dev data, not anything to protect.

#### 3. `src/main/resources/application.properties`

**File**: `src/main/resources/application.properties`

**Intent**: Configure the datasource and connection pool with safe local defaults that Render's real environment variables transparently override — the same pattern already used for `server.port`.

**Contract**: Add:
```properties
spring.datasource.url=${SPRING_DATASOURCE_URL:jdbc:postgresql://localhost:5432/deltabrief}
spring.datasource.username=${SPRING_DATASOURCE_USERNAME:deltabrief}
spring.datasource.password=${SPRING_DATASOURCE_PASSWORD:deltabrief}
spring.datasource.hikari.maximum-pool-size=5
```
The pool-size cap applies in both environments; it's a no-op locally and the mitigation `infrastructure.md`'s risk register already calls for against Supavisor's pooled connections.

#### 4. `src/main/resources/db/migration/` (new, empty directory)

**File**: `src/main/resources/db/migration/.gitkeep`

**Intent**: Establish Flyway's default migration location (`classpath:db/migration`) so the directory exists and is committed, ready for S-01's first real `V1__...sql` migration. Deliberately empty — no baseline migration needed given the `BIGSERIAL`/`IDENTITY` primary-key decision.

**Contract**: Flyway must run successfully at startup against zero migration files (creating only its own `flyway_schema_history` bookkeeping table) — this is expected, not an error condition.

### Success Criteria:

#### Automated Verification:

- Build compiles cleanly: `./gradlew build --no-daemon`
- Local app boots against Docker Postgres: `docker compose up -d && ./gradlew bootRun` starts without a datasource/Flyway error (manual-adjacent, but the underlying mechanism is proven automatically in Phase 2)

#### Manual Verification:

- `docker compose up -d`, then `./gradlew bootRun`, then confirm the startup log shows a successful Hikari pool initialization and Flyway reporting "Successfully validated 0 migrations" (or equivalent) with no errors
- `curl -i http://localhost:8080/actuator/health` returns `200` — note: once JPA is on the classpath, Spring Boot's Actuator auto-adds a `db` health indicator, so this also now implicitly verifies the datasource is reachable

**Implementation Note**: After completing this phase and automated verification passes, pause here for manual confirmation that local `bootRun` + `docker compose` works before proceeding to Phase 2.

---

## Phase 2: Prove it with a real, ephemeral Postgres

### Overview

Upgrade the existing context-loads test from a no-op boot check into a real proof that the datasource, Flyway, and JPA autoconfiguration all work together — using a genuine, throwaway Postgres container via Testcontainers, matching Supabase's Postgres 17.

### Changes Required:

#### 1. `src/test/java/pl/tul/deltabrief/DeltaBriefApplicationTests.java`

**File**: `src/test/java/pl/tul/deltabrief/DeltaBriefApplicationTests.java`

**Intent**: Replace the current no-op `contextLoads()` (which doesn't touch persistence) with a Testcontainers-backed version, so the same test now proves the full datasource + Flyway + JPA stack boots correctly against a real Postgres — not just that Spring itself starts.

**Contract**: Add `@Testcontainers` to the class; add a `static PostgreSQLContainer<?>` field pinned to `postgres:17`, annotated `@Container` and `@ServiceConnection` (per the Critical Implementation Details note above, add `name = "postgres"` to `@ServiceConnection` if the default startup throws `No ConnectionDetailsFactory found`). The existing `contextLoads()` method body stays empty — a successful Spring context load against the now-real, container-backed datasource *is* the assertion.

### Success Criteria:

#### Automated Verification:

- `./gradlew test --no-daemon` passes, with the test log showing a Testcontainers Postgres container starting and the Spring context loading against it
- `./gradlew build --no-daemon` passes end-to-end (full CI-equivalent gate)
- Pushing this change through the existing PR flow: `build-and-test` passes in GitHub Actions with **no `ci-cd.yml` changes** — confirming Docker-in-CI works out of the box on `ubuntu-latest`

#### Manual Verification:

- Review the CI run's log for the Testcontainers container-start lines, confirming it actually ran against a real container in the GitHub Actions environment (not silently skipped)

**Implementation Note**: After this phase's automated verification passes (including the real CI run, not just local), pause for confirmation before proceeding to Phase 3 — Phase 3 touches the deployed environment.

---

## Phase 3: Wire the deployed environment

### Overview

Create the real Supabase project and connect the deployed Render app to it, replacing local-only verification with proof that the actual production-path connection works.

### Changes Required:

#### 1. Supabase project (manual, browser-only)

**Intent**: Create a Supabase project (Free tier, per `infrastructure.md`'s budget decision) and retrieve its Supavisor transaction-mode (port 6543) connection details.

**Contract**: No repo file changes. Output needed: the pooler host (region-specific, e.g. `aws-0-<region>.pooler.supabase.com`), the project ref (for the `postgres.<project-ref>` username), and the database password — to be set as Render environment variables, not committed anywhere.

#### 2. Render environment variables (manual, browser-only)

**Intent**: Set the real datasource credentials on the already-live Render service so the deployed app connects to the real Supabase database instead of falling back to its (unreachable, from Render's network) local-default values.

**Contract**: On the `delta-brief` Render service, set `SPRING_DATASOURCE_URL` (`jdbc:postgresql://<pooler-host>:6543/postgres`), `SPRING_DATASOURCE_USERNAME` (`postgres.<project-ref>` — see Critical Implementation Details), and `SPRING_DATASOURCE_PASSWORD`. Trigger a redeploy (or wait for the next merge to `main`, which auto-deploys per the existing pipeline).

### Success Criteria:

#### Automated Verification:

- `curl -i https://delta-brief.onrender.com/actuator/health` returns `200` with the response body showing the `db` component as `UP` (confirms Actuator's auto-added DB health indicator is passing against the real Supabase connection, not just that the app booted)

#### Manual Verification:

- Render's deploy logs show a successful Hikari pool startup and Flyway validation against the real Supabase host (no connection-refused or authentication errors)
- Confirm in the Supabase dashboard that the connection actually registers (e.g., under Database → Connection Pooling stats) — proof the app is really reaching Supabase, not silently falling back to a local default

**Implementation Note**: This is the final phase — no further manual pause needed after its verification passes.

---

## Testing Strategy

### Unit Tests:

- None needed at this stage — there is no business logic yet, only infrastructure wiring.

### Integration Tests:

- The upgraded `DeltaBriefApplicationTests` (Phase 2) is the integration test: full Spring context load against a real Postgres container, covering datasource, connection pooling, Flyway, and JPA autoconfiguration together.

### Manual Testing Steps:

1. `docker compose up -d && ./gradlew bootRun` — confirm clean local startup against the local Postgres.
2. `./gradlew test` — confirm the Testcontainers-backed test passes locally.
3. After Phase 3, `curl https://delta-brief.onrender.com/actuator/health` — confirm `db: UP` against the real Supabase connection.

## Performance Considerations

`spring.datasource.hikari.maximum-pool-size=5` caps the connection pool in both environments — deliberately conservative against Supavisor's transaction-mode pooling limits (per `infrastructure.md`'s risk register), and irrelevant at local/MVP scale either way.

## Migration Notes

Not applicable — there is no existing data to migrate. The `db/migration` directory starts empty by design.

## References

- Foundation: `context/foundation/roadmap.md` § F-01 (wire-database-connectivity)
- Cost/tier decision: `context/foundation/infrastructure.md` (Supabase Free tier, Supavisor pooler, HikariCP cap)
- Existing config pattern this follows: `src/main/resources/application.properties` (`server.port=${PORT:8080}`)
- Spring Boot 4 Flyway migration guide: https://github.com/spring-projects/spring-boot/wiki/Spring-Boot-4.0-Migration-Guide

## Progress

> Convention: `- [ ]` pending, `- [x]` done. Append ` — <commit sha>` when a step lands. Do not rename step titles. See `references/progress-format.md`.

### Phase 1: Wire local dependencies and configuration

#### Automated

- [x] 1.1 Build compiles cleanly: `./gradlew build --no-daemon`
- [x] 1.2 Local app boots against Docker Postgres: `docker compose up -d && ./gradlew bootRun`

#### Manual

- [x] 1.3 Startup log shows successful Hikari + Flyway (0 migrations) against local Postgres
- [x] 1.4 `curl -i http://localhost:8080/actuator/health` returns 200

### Phase 2: Prove it with a real, ephemeral Postgres

#### Automated

- [ ] 2.1 `./gradlew test --no-daemon` passes with a real Testcontainers Postgres
- [ ] 2.2 `./gradlew build --no-daemon` passes end-to-end
- [ ] 2.3 GitHub Actions `build-and-test` passes with no `ci-cd.yml` changes

#### Manual

- [ ] 2.4 CI run log confirms a real Testcontainers container started (not skipped)

### Phase 3: Wire the deployed environment

#### Automated

- [ ] 3.1 `curl -i https://delta-brief.onrender.com/actuator/health` returns 200 with `db: UP`

#### Manual

- [ ] 3.2 Render deploy logs show successful connection to real Supabase
- [ ] 3.3 Supabase dashboard confirms an active connection from the app
