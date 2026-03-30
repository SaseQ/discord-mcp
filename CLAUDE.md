# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Overview

Fork of [SaseQ/discord-mcp](https://github.com/SaseQ/discord-mcp). A Spring Boot MCP server that exposes Discord guild operations as tools over the stdio transport. Uses JDA for Discord API access and Spring AI's MCP server starter for the protocol layer.

This fork exists to produce a multi-arch Docker image (`linux/amd64` + `linux/arm64`); upstream only publishes `linux/arm64`.

**Docker image**: `ghcr.io/panthrocorp-limited/discord-mcp`

## Build and Run

```bash
# Build the fat JAR and run tests
mvn clean verify

# Build skipping tests
mvn clean package -DskipTests

# Run a single test class
mvn test -Dtest=MessageServiceTest

# Run locally (requires DISCORD_TOKEN env var)
DISCORD_TOKEN=<token> DISCORD_GUILD_ID=<guild-id> java -jar target/discord-mcp-1.0.0.jar

# Docker build
docker build -t discord-mcp .

# Docker run
docker run --rm -i -e DISCORD_TOKEN=<token> -e DISCORD_GUILD_ID=<guild-id> discord-mcp
```

## Pre-commit

```bash
pre-commit install
pre-commit run --all-files
```

Hooks: merge conflict check, trailing whitespace, large file guard, gitleaks, detect-secrets, no-commit-to-main, no-push-to-main.

Secrets baseline: `.config/.secrets.baseline`. Regenerate with `detect-secrets scan --exclude-files '\.git/.*' > .config/.secrets.baseline`.

## CI/CD

`.github/workflows/ci.yml` runs on PRs and pushes to main. Three jobs: pre-commit, `mvn verify`, and Trivy container vulnerability scan (SARIF results uploaded to GitHub Security tab).

`.github/workflows/build.yml` runs on push to main. Gates on a test job passing before building a multi-arch Docker image and pushing to GHCR.

## Syncing Upstream

```bash
git fetch upstream
git merge upstream/main
```

Review changes to `pom.xml`, `Dockerfile`, or service classes before merging.

## Architecture

**Stack**: Java 17, Spring Boot 4.0.2, Spring AI 2.0.0-M2 (MCP server BOM), JDA 5.6.1

**Transport**: stdio only (HTTP disabled via `spring.main.web-application-type=none` in `application.properties`). Console logging and the Spring banner are suppressed to avoid corrupting the stdio stream.

**Entry point**: `DiscordMcpApplication` (standard Spring Boot main class).

**Configuration**: `DiscordMcpConfig` is the sole `@Configuration` class. It:
1. Creates the JDA client bean from `DISCORD_TOKEN`, enabling `GUILD_MEMBERS`, `GUILD_VOICE_STATES`, `SCHEDULED_EVENTS`, and `MESSAGE_CONTENT` gateway intents.
2. Registers all service classes as MCP tools via `MethodToolCallbackProvider`.

**Service pattern**: Each `@Service` class in `dev.saseq.services` groups related Discord operations. Public methods annotated with `@Tool` become MCP tools. Methods use `@ToolParam` for parameter metadata. All JDA calls use `.complete()` (blocking) rather than async. Services that accept a `guildId` parameter fall back to the `DISCORD_GUILD_ID` env var when omitted.

**Services**: `DiscordService` (guild info), `MessageService`, `UserService`, `ChannelService`, `CategoryService`, `WebhookService`, `ThreadService`, `RoleService`, `ModerationService`, `VoiceChannelService`, `ScheduledEventService`, `InviteService`, `ChannelPermissionService`, `EmojiService`.

## Testing

Unit tests mock JDA using Mockito (`@ExtendWith(MockitoExtension.class)`). The integration test uses `@MockitoBean` to replace the JDA bean, avoiding a real Discord connection.

JDA methods like `retrieveMemberById` return `CacheRestAction`, not `RestAction`. Use the exact JDA return type when mocking to avoid compilation errors.

Use `ReflectionTestUtils.setField(service, "defaultGuildId", "value")` to set the `@Value`-injected field in unit tests.

## Key Conventions

- Tool names use `snake_case` (e.g. `send_message`, `get_server_info`).
- Parameter validation is manual null/empty checks throwing `IllegalArgumentException`.
- Responses are plain-text formatted strings, not JSON.
- The `resolveGuildId` helper pattern (fall back to default guild) is duplicated across services that need it.

## Environment Variables

| Variable | Required | Purpose |
|----------|----------|---------|
| `DISCORD_TOKEN` | Yes | Discord bot token |
| `DISCORD_GUILD_ID` | No | Default guild ID; makes `guildId` param optional on all tools |
