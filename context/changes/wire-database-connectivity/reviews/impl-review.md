<!-- IMPL-REVIEW-REPORT -->
# Implementation Review: Wire Database Connectivity Implementation Plan

- **Plan**: context/changes/wire-database-connectivity/plan.md
- **Scope**: Full plan (Phases 1-3 of 3)
- **Date**: 2026-09-11
- **Verdict**: NEEDS ATTENTION
- **Findings**: 0 critical, 4 warnings, 1 observation

## Verdicts

| Dimension | Verdict |
|-----------|---------|
| Plan Adherence | WARNING |
| Scope Discipline | WARNING |
| Safety & Quality | WARNING |
| Architecture | PASS |
| Pattern Consistency | WARNING |
| Success Criteria | PASS |

## Findings

### F1 — Testcontainers wiring implemented via a different mechanism than planned

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Plan Adherence
- **Location**: context/changes/wire-database-connectivity/plan.md (Phase 2 contract) vs. src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java
- **Detail**: The plan specified `@Testcontainers` + `@Container` + `@ServiceConnection` directly on `DeltaBriefApplicationTests`. The actual implementation uses a separate `@TestConfiguration` class with manually-managed `HikariDataSource` beans gated by a `CI`/`!CI` Spring profile (set via `build.gradle`'s `test` task, not read in application code). This was a necessary, well-documented pivot: `change.md`'s Notes explain that a VPN breaks Docker's default bridge networking locally, forcing a host-network `GenericContainer` for local runs, and that `@ServiceConnection`'s automatic detection doesn't support `GenericContainer`/host networking. The substitute mechanism still achieves the plan's verification intent — a real, ephemeral `postgres:17` container proving datasource + Flyway + JPA work together, in both CI (bridge network, Ryuk-managed) and local (host network, shutdown-hook-managed) modes. The only gap is that `plan.md`'s Phase 2 "Changes Required" text was never updated to reflect the actual design, even though `change.md` carries the rationale.
- **Fix**: Add a short addendum to `plan.md`'s Phase 2 section (or a note above `## Progress`) pointing to `change.md`'s Notes and summarizing the actual mechanism, so the plan remains an accurate record without needing any code change.
- **Decision**: FIXED — addendum added to plan.md's Phase 2 contract section.

### F2 — Log4j2/Lombok switch not reflected in the plan

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Scope Discipline
- **Location**: build.gradle, src/main/resources/log4j2.xml, src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java
- **Detail**: Swapping Spring Boot's default Logback for Log4j2, adding Lombok, and using `@Log4j2` to log which datasource is being initialized were all added mid-implementation at the user's explicit request (confirmed in the conversation and split into their own dedicated commit, `3fb35bc`) — not a silent addition. `plan.md`'s Phase 2 contract, however, only describes the Testcontainers test change and says nothing about logging infrastructure.
- **Fix**: Add a one-line addendum to `plan.md` or `change.md`'s Notes recording that Log4j2/Lombok were introduced at explicit user request during Phase 2, for future readers of the plan.
- **Decision**: FIXED — addendum added to plan.md's Phase 2 contract section.

### F3 — Local Testcontainers container has no self-healing if a hard kill leaks it

- **Severity**: ⚠️ WARNING
- **Impact**: 🔎 MEDIUM — real tradeoff; pause to reason through it
- **Dimension**: Safety & Quality
- **Location**: src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java:76-88 (localDataSource)
- **Detail**: Cleanup of the local, host-network container relies solely on a JVM shutdown hook, with Ryuk explicitly disabled (Ryuk doesn't reliably manage host-network containers). A graceful stop (Ctrl+C, IDE "stop", normal test completion) runs the shutdown hook fine, but a hard kill (`kill -9`, OOM-killer, crash) skips it entirely — leaking a container that's bound to the *fixed* host port 32785. The next local test run would then fail to bind that port, with no automatic recovery; the developer would need to manually `docker ps` / `docker rm` the orphan.
- **Fix A ⭐ Recommended**: Before starting a new local container, look up and force-remove any existing container already using the fixed port/name (via the Docker Java client's list-then-remove API, matched by a well-known container name or label), so a prior leak self-heals on the next run instead of failing opaquely.
  - Strength: Makes local test runs resilient to a leaked container from a prior crash, with no manual intervention needed.
  - Tradeoff: A bit more code in test-only infrastructure, plus one extra Docker API round-trip at every local test startup.
  - Confidence: MED — the self-healing pattern is standard practice for exactly this pitfall, but the precise Docker Java client call needs to be written and tested.
  - Blind spot: Haven't verified the exact Docker Java client API surface needed for the list-and-remove-by-name/label query.
- **Fix B**: Accept the risk and document it — add a comment noting that a hard-killed JVM can leak this container, recoverable via `docker ps` + `docker rm`.
  - Strength: Zero code risk, keeps test-only infrastructure simple.
  - Tradeoff: Leaves a real, if rare, manual-cleanup burden on the developer.
  - Confidence: HIGH — genuinely rare in normal usage; both Ctrl+C and IDE "stop" run shutdown hooks correctly.
  - Blind spot: None significant.
- **Decision**: FIXED via Fix A — added `removeStaleLocalContainer()` (Docker Java client list-then-force-remove by a well-known container name, `LOCAL_CONTAINER_NAME`), called before `localDataSource()` starts a new container. Verified by manually starting a container under that name/port to simulate a leak, then confirming the test run logged `>>> Removing stale local Testcontainers PostgreSQL container: <id>` and the stale container was gone from `docker ps -a` afterward.

### F4 — Fixed local port assumes only one Spring test context

- **Severity**: ⚠️ WARNING
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Pattern Consistency
- **Location**: src/test/java/pl/tul/deltabrief/config/TestcontainersDatasourceConfig.java:49 (FIXED_LOCAL_PORT), 69-91 (localDataSource)
- **Detail**: The local datasource bean binds to a hardcoded fixed port (32785) so the JDBC URL can be constructed without a `@DynamicPropertySource`. This is safe today (exactly one test class, one Spring context, exists in the project), but a future integration test class that forces a *different* Spring context (e.g. different active profiles or mocked beans) would trigger a second invocation of `localDataSource()`, attempting to rebind the same fixed port while the first context's container is still running.
- **Fix**: Add a Javadoc note on `FIXED_LOCAL_PORT` / `localDataSource()` documenting the single-Spring-context assumption, so a future contributor adding a second integration test class with a different context configuration knows to revisit this design.
- **Decision**: SKIPPED

### F5 — Local dev Postgres listens on all host network interfaces, not just loopback

- **Severity**: 🔵 OBSERVATION
- **Impact**: 🏃 LOW — quick decision; fix is obvious and narrowly scoped
- **Dimension**: Safety & Quality
- **Location**: docker-compose.yml:1-20
- **Detail**: `network_mode: host` (required to work around the local VPN/Docker bridge-networking conflict) means the containerized Postgres listens on all host interfaces (the default Postgres image's `listen_addresses='*'`), not just loopback — unlike bridge mode's `127.0.0.1:PORT:PORT` mapping, compose alone can't restrict the bind address here. On a machine attached to a VPN/corporate LAN, this is technically reachable by other hosts on that network segment using the known dev credentials. Low severity: this is a local dev-only database with intentionally non-secret, fixed credentials (`deltabrief`/`deltabrief`), and the tradeoff was already made consciously to fix the VPN issue — but it's a real behavior change from bridge mode's implicit loopback-only exposure, worth being aware of.
- **Fix**: Optional — if this machine is ever used on an untrusted network, restrict access via `pg_hba.conf` or a host firewall rule; otherwise, no action needed beyond awareness.
- **Decision**: SKIPPED — accepted tradeoff, already made consciously to fix the VPN issue.

## Success Criteria Verification

**Automated** (re-run during this review, 2026-09-11):
- `./gradlew clean build --no-daemon` → BUILD SUCCESSFUL (local host-network Testcontainers path exercised)
- `curl -i https://delta-brief.onrender.com/actuator/health` → `200`, `{"status":"UP"}`

**Manual** (Progress section cross-checked against evidence in this session's history — not rubber-stamped):
- 1.3/1.4, 2.4, 3.2/3.3 all carry SHA references and were confirmed with concrete evidence at the time (Hikari/Flyway startup logs, CI run timing, direct Render deploy log excerpts showing `HikariPool-1` connecting to `aws-1-eu-west-1.pooler.supabase.com:6543` and Flyway creating `flyway_schema_history` against Supabase Postgres 17.6, with no errors in the boot log).

No missing or falsely-checked manual items found.
