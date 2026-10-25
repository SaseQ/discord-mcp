import { McpServer } from "@modelcontextprotocol/server";
import pkg from "../package.json" with { type: "json" };
import { ConfigError } from "./config.ts";
import type { Context } from "./context.ts";
import {
  isEnabled,
  type ToolPolicy,
  type ToolTraits,
  unknownSelectors,
} from "./policy.ts";
import type { Tool } from "./tool.ts";

export const SERVER_NAME = "discord-mcp";

/** Tells tool search when to look here, and states the conventions that the schemas leave out. */
const INSTRUCTIONS = `Discord server (guild) tools: messages, DMs, threads, forums, channels, categories, roles, members, moderation (kick, ban, timeout), permissions, voice, webhooks, invites, emojis, scheduled events.
Use for any Discord message, channel, server, user mention, or message link (discord.com/channels/...).
IDs are Discord snowflakes (17-20 digits). guildId defaults to DISCORD_GUILD_ID. reason is written to the audit log. A message link holds guildId/channelId/messageId.`;

/** Returns the tools that the policy enables, or throws when a selector matches no tool. */
export const selectTools = (
  tools: readonly Tool[],
  policy: ToolPolicy,
): Tool[] => {
  const unknown = unknownSelectors(
    [...policy.allow, ...policy.deny],
    tools.flatMap((t): ToolTraits[] => [...(t.parts?.list ?? [t])]),
  );
  if (unknown.length > 0) {
    throw new ConfigError(
      `Tool selectors that match no tool: ${unknown.join(", ")}. Run \`bun run tools\` to list the tools.`,
    );
  }
  return tools.flatMap((tool) => {
    if (!tool.parts) return isEnabled(policy, tool) ? [tool] : [];
    const on = tool.parts.list.filter((part) => isEnabled(policy, part));
    if (on.length === 0) return [];
    return [on.length === tool.parts.list.length ? tool : tool.parts.limit(on)];
  });
};

export const createServer = (
  tools: readonly Tool[],
  ctx: Context,
): McpServer => {
  const server = new McpServer(
    { name: SERVER_NAME, version: pkg.version },
    {
      instructions: INSTRUCTIONS,
      // The tool list changes only on a restart with a new allowlist.
      cacheHints: { "tools/list": { ttlMs: 3_600_000, cacheScope: "private" } },
    },
  );
  for (const tool of tools) tool.register(server, ctx);
  return server;
};
