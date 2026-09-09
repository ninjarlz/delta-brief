# Repository Guidelines

DeltaBrief is a Spring Boot 4 web app (Java 21, Gradle) that generates *delta briefings* — summaries of what **changed** about a news topic since the user's last briefing, not what's newest. The repo is freshly scaffolded: only the Spring Boot entrypoint exists. Auth, briefing generation, and scheduling are specified but not yet built.

## Hard rules

- **Never let a briefing fabricate facts.** Every claim in generated briefing content must be traceable to an ingested source — hallucination is the product's #1 trust risk (guardrail in `@context/foundation/prd.md`). Any prompt, model call, or output-rendering code must preserve source attribution.
- **Do not modify anything under `context/`.** It is the source of truth for the PRD, the stack decision, and the bootstrap trail. Read it; never overwrite it. Start at `@context/foundation/prd.md`.
- **Keep Spring AI on the 2.0.x line.** Spring AI 2.0 targets Spring Boot 4; 1.1.x targets Boot 3. Mixing them breaks the build. The version is pinned as `springAiVersion` in `@build.gradle`.
- **Never commit the LLM API key.** It is read from the environment as `spring.ai.openai.api-key` — never hard-code it or commit it.
- **Server-rendered + session auth — not an API.** Thymeleaf pages + HTMX fragments, Spring Security session login; no `@RestController`/JWT. See `@context/foundation/tech-stack.md`.

## Project structure

- App code: `src/main/java/pl/tul/deltabrief/` — everything nests under `pl.tul.deltabrief`.
- Entry point: `@src/main/java/pl/tul/deltabrief/DeltaBriefApplication.java`.
- Runtime config: `src/main/resources/application.properties`.
- Build: `@build.gradle` / `@settings.gradle`. Product requirements live in `context/foundation/`.

## Build, test, and run

- `./gradlew bootRun` — run locally (devtools live-reload is on).
- `./gradlew test` — run the JUnit 5 suite.
- `./gradlew test --tests "pl.tul.deltabrief.<ClassName>"` — run one test class.
- `./gradlew build` — full build plus tests.

Always use the wrapper (`./gradlew`), not a system `gradle`.

## Coding style

- Java 21; tab indentation (match the existing scaffold files).
- Put new code in feature packages under `pl.tul.deltabrief` (e.g. `.topic`, `.briefing`, `.auth`) — not the root package.
- No linter or formatter is configured; there is no automated style gate.

## Testing

- Follow the sample test at `@src/test/java/pl/tul/deltabrief/DeltaBriefApplicationTests.java`; name test classes `<Unit>Tests`.

## Configuration & secrets

- LLM access uses the Spring AI OpenAI starter. Swap the model starter in `@build.gradle` to change providers (each provider has its own `spring.ai.<provider>.api-key`).

## Commit & PR

- Not a git repo yet — run `git init` first. Commit convention is undefined; adopt Conventional Commits unless the team decides otherwise.
