# Review calibration

This is a maintained fork of [`SaseQ/discord-mcp`](https://github.com/SaseQ/discord-mcp).
Upstream has not merged to `main` since 2026-04-25, so fixes land here first and
this fork is what gets deployed.

It runs as an MCP server driven by an LLM agent. That shapes what matters in review.

## What matters most here

**Every tool is reachable by a model, and models can be manipulated.** Assume any
tool can be invoked with attacker-influenced arguments, because prompt injection
into an agent's context is a normal event rather than a hypothetical. A tool that
is safe when a careful human calls it is not automatically safe here.

**Caller-supplied URLs are SSRF vectors.** Anything that fetches a URL must go
through `RemoteFetchGuard`: https only, resolvable public host, no redirect
following, bounded read, finite timeouts. Do not add a second download
implementation.

This has already gone wrong once. `createEmoji` was hardened, and then
`send_file` shipped with an unguarded `URI.create(url).toURL().openStream()`
because the guard was a private method rather than shared code.

**Local filesystem reads are exfiltration vectors.** Any tool that reads a
caller-supplied path must confine it to an allowlisted root, normalised and
prefix-checked. An unconstrained read, on a service that loads secrets from its
environment, means one tool call can post credentials into a chat channel.

**New write tools should be individually grantable.** Deployments filter tools by
name, so a narrowly scoped tool can be allowed while a broad one stays denied.
Prefer several specific tools over one general one. A generic write forces
operators to grant everything or nothing.

**Do not widen a tool's blast radius quietly.** Adding a parameter that lets an
existing tool reach new resources is a bigger change than it looks, because
deployments have already decided whether that tool is allowed.

## Ordinary quality bar

- Validate inputs and fail with a message that says what was wrong.
- Bound anything unbounded: response sizes, result counts, loops over API pages.
- Respect Discord rate limits. Do not add unmetered call patterns.
- Match the existing conventions in `src/main/java/dev/saseq/services/` rather
  than introducing a new pattern.
- Tests follow the pattern in `src/test/java/dev/saseq/services/`.

## Known gaps, deliberately open

- **No `recurrence_rule` support for scheduled events**
  ([#3](https://github.com/BASIC-BIT/discord-mcp/issues/3)). JDA cannot express
  the field. Editing a recurring event changes one occurrence while the series
  re-anchors, so the tool reports success for a change that did not take.
- Upstream PR `#37` overlaps this fork's member reconciliation work. Track it
  rather than duplicating it.
