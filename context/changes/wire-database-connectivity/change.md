---
change_id: wire-database-connectivity
title: Wire database connectivity
status: implemented
created: 2026-09-10
updated: 2026-09-11
archived_at: null
---

## Notes

**Environment-specific deviation, discovered during Phase 1 (not a plan flaw):** this machine's corporate VPN breaks Docker's default bridge networking — TCP handshakes complete but the Postgres protocol connection gets reset before completing. Fix: `docker-compose.yml` uses `network_mode: host` with the containerized Postgres listening on port 5433 directly (5432 is taken by a native system Postgres, unrelated to this project). Confirmed working end-to-end.

This same VPN constraint means **Phase 2's Testcontainers-backed test needs a CI/local dual-mode split**, not a single `@ServiceConnection`-annotated `PostgreSQLContainer` as originally planned:
- **CI** (GitHub Actions, no VPN): normal `PostgreSQLContainer` + bridge networking + dynamic port + Ryuk enabled — the original plan, unchanged.
- **Local** (this machine): a `GenericContainer` with Docker host network mode, a fixed port, Ryuk disabled, and a JVM shutdown hook for cleanup (Ryuk doesn't reliably manage host-network containers) — wired via `@DynamicPropertySource` instead of `@ServiceConnection`, since `@ServiceConnection`'s automatic container-type detection doesn't support `GenericContainer`/host networking.

Reference implementation for this exact pattern: `TestcontainersDatasourceFactory` in the user's `xapa-trade-reporter` project (Micronaut, not Spring Boot — translate the pattern, not the framework-specific annotations).
