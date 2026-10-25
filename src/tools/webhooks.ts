import { DiscordRESTError } from "oceanic.js";
import { z } from "zod";
import { defineTool, ToolError } from "../tool.ts";
import { mentionPolicy } from "./format.ts";
import { auditReason, snowflake } from "./schemas.ts";
import { hasStatus, reasonOption } from "./util.ts";

const WEBHOOK_PATH = /^\/api\/webhooks\/(\d{17,20})\/([\w-]+)\/?$/;
const DISCORD_HOSTS = new Set(["discord.com", "discordapp.com"]);

/**
 * Reads the webhook ID and token from a Discord webhook link.
 * The error never repeats the link, because the link holds the token.
 */
const parseWebhookUrl = (value: string) => {
  let url: URL;
  try {
    url = new URL(value);
  } catch {
    throw new ToolError("webhookUrl must be a Discord webhook link");
  }
  const match = WEBHOOK_PATH.exec(url.pathname);
  const webhookId = match?.[1];
  const token = match?.[2];
  if (
    url.protocol !== "https:" ||
    !DISCORD_HOSTS.has(url.hostname) ||
    webhookId === undefined ||
    token === undefined
  )
    throw new ToolError("webhookUrl must be a Discord webhook link");
  return { webhookId, token };
};

/** Maps a failed send to an error whose text does not contain the token. */
const sendFailure = (error: unknown, token: string): Error => {
  if (hasStatus(error, 404))
    return new ToolError("Webhook not found by webhookUrl");
  if (error instanceof DiscordRESTError)
    return new ToolError(
      `Discord rejected the webhook message (HTTP ${error.status})`,
    );
  const text = error instanceof Error ? error.message : String(error);
  return new Error(text.replaceAll(token, "[redacted]"));
};

export const webhookTools = [
  defineTool({
    name: "create_webhook",
    title: "Create webhook",
    description: "Create a channel webhook. The URL is returned only once.",
    group: "webhooks",
    access: "write",
    input: z.object({
      channelId: snowflake("Channel ID"),
      name: z.string().min(1).max(80),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const webhook = await ctx.client.rest.webhooks
        .create(args.channelId, {
          name: args.name,
          ...reasonOption(args.reason),
        })
        .catch((error: unknown) => {
          if (hasStatus(error, 404))
            throw new ToolError("Channel not found by channelId");
          throw error;
        });
      return {
        id: webhook.id,
        name: webhook.name,
        url: webhook.url,
      };
    },
  }),
  defineTool({
    name: "delete_webhook",
    title: "Delete webhook",
    description: "Delete a webhook.",
    group: "webhooks",
    access: "destructive",
    input: z.object({
      webhookId: snowflake("Webhook ID"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const webhook = await ctx.client.rest.webhooks
        .get(args.webhookId)
        .catch((error: unknown) => {
          if (hasStatus(error, 404))
            throw new ToolError("Webhook not found by webhookId");
          throw error;
        });
      await ctx.client.rest.webhooks.delete(args.webhookId, args.reason);
      return { id: webhook.id, name: webhook.name, deleted: true };
    },
  }),
  defineTool({
    name: "list_webhooks",
    title: "List webhooks",
    description: "List a channel's webhooks (IDs and names, never URLs).",
    group: "webhooks",
    access: "read",
    input: z.object({ channelId: snowflake("Channel ID") }),
    run: async (args, ctx) => {
      const webhooks = await ctx.client.rest.webhooks.getForChannel(
        args.channelId,
      );
      return webhooks.map((webhook) => ({
        id: webhook.id,
        name: webhook.name,
        channelId: args.channelId,
      }));
    },
  }),
  defineTool({
    name: "send_webhook_message",
    title: "Send webhook message",
    description: "Send a message through a Discord webhook URL.",
    group: "webhooks",
    access: "write",
    input: z.object({
      webhookUrl: z
        .string()
        .max(300)
        .describe("https://discord.com/api/webhooks/{id}/{token}"),
      message: z.string().min(1).max(2000),
    }),
    run: async (args, ctx) => {
      const { webhookId, token } = parseWebhookUrl(args.webhookUrl);
      let sentId: string;
      try {
        const sent = await ctx.client.rest.webhooks.execute(webhookId, token, {
          content: args.message,
          allowedMentions: mentionPolicy(false),
          wait: true,
        });
        sentId = sent.id;
      } catch (error) {
        throw sendFailure(error, token);
      }
      // The message is sent at this point, so a failed lookup of the link must not report a failure that a caller would retry.
      const webhook = await ctx.client.rest.webhooks
        .get(webhookId, token)
        .catch(() => null);
      return {
        id: sentId,
        channelId: webhook?.channelID ?? null,
        link: webhook?.guildID
          ? `https://discord.com/channels/${webhook.guildID}/${webhook.channelID}/${sentId}`
          : null,
      };
    },
  }),
];
