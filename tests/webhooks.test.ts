import { describe, expect, test } from "bun:test";
import { webhookTools } from "../src/tools/webhooks.ts";
import { callTool, fakeContext, recorder, restError } from "./harness.ts";

const CHANNEL = "500000000000000001";
const WEBHOOK = "600000000000000001";
const TOKEN = "abcDEF123_secret-token";
const URL_WITH_TOKEN = `https://discord.com/api/webhooks/${WEBHOOK}/${TOKEN}`;
const GUILD = "111111111111111111";
const MESSAGE = "700000000000000001";

const webhook = {
  id: WEBHOOK,
  name: "alerts",
  channelID: CHANNEL,
  guildID: GUILD,
  token: TOKEN,
  url: URL_WITH_TOKEN,
};

describe("list_webhooks", () => {
  test("returns the ID, name, and channel only, never the token or URL", async () => {
    const ctx = fakeContext({
      webhooks: { getForChannel: async () => [webhook] },
    });
    const result = await callTool(webhookTools, ctx, "list_webhooks", {
      channelId: CHANNEL,
    });
    expect(result.isError).toBe(false);
    expect(result.data).toEqual([
      { id: WEBHOOK, name: "alerts", channelId: CHANNEL },
    ]);
    expect(JSON.stringify(result.data)).not.toContain(TOKEN);
  });
});

describe("send_webhook_message", () => {
  test("rejects a URL that is not a Discord webhook before any call", async () => {
    const execute = recorder({ id: MESSAGE });
    const ctx = fakeContext({ webhooks: { execute } });
    const result = await callTool(webhookTools, ctx, "send_webhook_message", {
      webhookUrl: `https://example.com/api/webhooks/${WEBHOOK}/${TOKEN}`,
      message: "hi",
    });
    expect(result.isError).toBe(true);
    expect(execute.calls).toHaveLength(0);
    expect(JSON.stringify(result.data)).not.toContain(TOKEN);
  });

  test("builds the message link from the webhook's guild and channel", async () => {
    const ctx = fakeContext({
      webhooks: {
        execute: async () => ({ id: MESSAGE, channelID: CHANNEL }),
        get: async () => webhook,
      },
    });
    const result = await callTool(webhookTools, ctx, "send_webhook_message", {
      webhookUrl: URL_WITH_TOKEN,
      message: "hi",
    });
    expect(result.data).toEqual({
      id: MESSAGE,
      channelId: CHANNEL,
      link: `https://discord.com/channels/${GUILD}/${CHANNEL}/${MESSAGE}`,
    });
  });

  test("reports success without a link when the webhook lookup fails after the send", async () => {
    const execute = recorder({ id: MESSAGE, channelID: CHANNEL });
    const ctx = fakeContext({
      webhooks: {
        execute,
        get: async () => {
          throw restError(500);
        },
      },
    });
    const result = await callTool(webhookTools, ctx, "send_webhook_message", {
      webhookUrl: URL_WITH_TOKEN,
      message: "@everyone hi",
    });
    expect(result).toEqual({
      isError: false,
      data: { id: MESSAGE },
    });
    const options = execute.calls[0]?.[2] as {
      allowedMentions: { everyone: boolean; roles: boolean };
    };
    expect(options.allowedMentions.everyone).toBe(false);
    expect(options.allowedMentions.roles).toBe(false);
  });

  test("never returns the token, even when Discord rejects the message", async () => {
    const ctx = fakeContext({
      webhooks: {
        execute: async () => {
          throw restError(500, 0, "POST");
        },
      },
    });
    const result = await callTool(webhookTools, ctx, "send_webhook_message", {
      webhookUrl: URL_WITH_TOKEN,
      message: "hi",
    });
    expect(result.isError).toBe(true);
    expect(JSON.stringify(result.data)).not.toContain(TOKEN);
  });

  test("redacts the token from an unexpected error message", async () => {
    const ctx = fakeContext({
      webhooks: {
        execute: async () => {
          throw new Error(`failed for ${URL_WITH_TOKEN}`);
        },
      },
    });
    const result = await callTool(webhookTools, ctx, "send_webhook_message", {
      webhookUrl: URL_WITH_TOKEN,
      message: "hi",
    });
    expect(result.isError).toBe(true);
    expect(JSON.stringify(result.data)).not.toContain(TOKEN);
  });
});
