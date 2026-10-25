import { describe, expect, test } from "bun:test";
import { ChannelTypes } from "oceanic.js";
import { categoryTools } from "../src/tools/categories.ts";
import { callTool, DEFAULT_GUILD, fakeContext, recorder } from "./harness.ts";

const INFO = "300000000000000001";
const ARCHIVE = "300000000000000002";
const TEXT_IN_INFO = "200000000000000001";
const TEXT_LOOSE = "200000000000000003";
const TEXT_IN_ARCHIVE = "200000000000000004";

/** A guild channel as Oceanic returns it, with the fields that the tools read. */
const channel = (over: Record<string, unknown>) => ({
  id: TEXT_IN_INFO,
  name: "general",
  type: ChannelTypes.GUILD_TEXT,
  parentID: null,
  position: 0,
  topic: null,
  nsfw: false,
  rateLimitPerUser: 0,
  createdAt: new Date("2020-01-01T00:00:00.000Z"),
  ...over,
});

const guildChannels = [
  channel({ id: INFO, name: "Info", type: ChannelTypes.GUILD_CATEGORY }),
  channel({ id: ARCHIVE, name: "Archive", type: ChannelTypes.GUILD_CATEGORY }),
  channel({ id: TEXT_IN_INFO, name: "rules", parentID: INFO }),
  channel({ id: TEXT_LOOSE, name: "lobby", parentID: null }),
  channel({ id: TEXT_IN_ARCHIVE, name: "old", parentID: ARCHIVE }),
];

describe("find_category", () => {
  test("finds the one category with the name, without case", async () => {
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
    });

    const result = await callTool(categoryTools, ctx, "find_category", {
      categoryName: "info",
    });

    expect(result.isError).toBe(false);
    expect(result.data).toEqual(
      expect.objectContaining({ id: INFO, type: "category" }),
    );
  });

  test("fails when two categories have the name", async () => {
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [
          channel({
            id: INFO,
            name: "Info",
            type: ChannelTypes.GUILD_CATEGORY,
          }),
          channel({
            id: ARCHIVE,
            name: "INFO",
            type: ChannelTypes.GUILD_CATEGORY,
          }),
        ],
      },
    });

    const result = await callTool(categoryTools, ctx, "find_category", {
      categoryName: "info",
    });

    expect(result.isError).toBe(true);
  });

  test("ignores a text channel with the same name", async () => {
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [
          channel({
            id: TEXT_LOOSE,
            name: "info",
            type: ChannelTypes.GUILD_TEXT,
          }),
        ],
      },
    });

    const result = await callTool(categoryTools, ctx, "find_category", {
      categoryName: "info",
    });

    expect(result.isError).toBe(true);
  });
});

describe("list_channels_in_category", () => {
  test("lists only the channels whose parent is the category", async () => {
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
    });

    const result = await callTool(
      categoryTools,
      ctx,
      "list_channels_in_category",
      { categoryId: INFO },
    );

    expect(result.isError).toBe(false);
    expect(result.data).toEqual([
      expect.objectContaining({ id: TEXT_IN_INFO, parentId: INFO }),
    ]);
  });

  test("fails for an ID that is not a category of the server", async () => {
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
    });

    const result = await callTool(
      categoryTools,
      ctx,
      "list_channels_in_category",
      { categoryId: TEXT_LOOSE },
    );

    expect(result.isError).toBe(true);
  });
});

describe("delete_category", () => {
  test("deletes the category with the audit reason", async () => {
    const remove = recorder(undefined);
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
      channels: { delete: remove },
    });

    const result = await callTool(categoryTools, ctx, "delete_category", {
      categoryId: INFO,
      reason: "retired",
    });

    expect(result.isError).toBe(false);
    expect(remove.calls).toEqual([[INFO, "retired"]]);
  });

  test("rejects a channel that is not a category before deleting", async () => {
    const remove = recorder(undefined);
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
      channels: { delete: remove },
    });

    const result = await callTool(categoryTools, ctx, "delete_category", {
      categoryId: TEXT_LOOSE,
    });

    expect(result.isError).toBe(true);
    expect(remove.calls).toEqual([]);
  });
});

describe("edit_category", () => {
  test("sends only the name when only the name is given", async () => {
    const edit = recorder(channel({ id: INFO, name: "Guides" }));
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
      channels: { edit },
    });

    const result = await callTool(categoryTools, ctx, "edit_category", {
      categoryId: INFO,
      name: "Guides",
    });

    expect(result.isError).toBe(false);
    expect(edit.calls).toEqual([[INFO, { name: "Guides" }]]);
  });

  test("fails with neither a name nor a position, without editing", async () => {
    const edit = recorder(channel({ id: INFO }));
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels },
      channels: { edit },
    });

    const result = await callTool(categoryTools, ctx, "edit_category", {
      categoryId: INFO,
    });

    expect(result.isError).toBe(true);
    expect(edit.calls).toEqual([]);
  });
});

describe("create_category", () => {
  test("creates a category in the given server", async () => {
    const create = recorder(
      channel({ id: INFO, name: "Info", type: ChannelTypes.GUILD_CATEGORY }),
    );
    const ctx = fakeContext({
      guilds: { createChannel: create },
    });

    const result = await callTool(categoryTools, ctx, "create_category", {
      guildId: DEFAULT_GUILD,
      name: "Info",
    });

    expect(result.isError).toBe(false);
    expect(create.calls).toEqual([
      [DEFAULT_GUILD, ChannelTypes.GUILD_CATEGORY, { name: "Info" }],
    ]);
  });
});
