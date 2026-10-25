import { describe, expect, test } from "bun:test";
import { ChannelFlags, ChannelTypes } from "oceanic.js";
import { forumTools } from "../src/tools/forums.ts";
import { callTool, DEFAULT_GUILD, fakeContext, recorder } from "./harness.ts";

const FORUM = "200000000000000001";
const BUG = "300000000000000001";
const IDEA = "300000000000000002";
const POST = "400000000000000001";

const tags = [
  { id: BUG, name: "Bug", moderated: false, emoji: null },
  { id: IDEA, name: "Idea", moderated: false, emoji: null },
];

const forum = {
  id: FORUM,
  type: ChannelTypes.GUILD_FORUM,
  name: "ideas",
  guildID: DEFAULT_GUILD,
  parentID: null,
  topic: null,
  nsfw: false,
  rateLimitPerUser: 0,
  position: 0,
  availableTags: tags,
};

const post = (flags: number) => ({
  id: POST,
  type: ChannelTypes.PUBLIC_THREAD,
  name: "hello",
  guildID: DEFAULT_GUILD,
  parentID: FORUM,
  flags,
  appliedTags: [],
  threadMetadata: { archived: false, locked: false },
});

describe("forum tags", () => {
  test("create_forum_post sends the tag IDs that the caller gave by ID or by name", async () => {
    const start = recorder({
      id: POST,
      name: "hello",
      appliedTags: [BUG, IDEA],
    });
    const ctx = fakeContext({
      guilds: { getChannels: async () => [forum] },
      channels: { startThreadInThreadOnlyChannel: start },
    });
    const result = await callTool(forumTools, ctx, "create_forum_post", {
      channelId: FORUM,
      title: "hello",
      message: "first",
      tagIds: `${BUG}, idea`,
    });
    expect(result.isError).toBe(false);
    const [, options] = start.calls[0] as [string, { appliedTags: string[] }];
    expect(options.appliedTags).toEqual([BUG, IDEA]);
  });

  test("create_forum_post does not ping @everyone or roles", async () => {
    const start = recorder({ id: POST, name: "hello", appliedTags: [] });
    const ctx = fakeContext({
      guilds: { getChannels: async () => [forum] },
      channels: { startThreadInThreadOnlyChannel: start },
    });
    await callTool(forumTools, ctx, "create_forum_post", {
      channelId: FORUM,
      title: "hello",
      message: "@everyone first",
    });
    const [, options] = start.calls[0] as [
      string,
      { message: { allowedMentions: { everyone: boolean; roles: boolean } } },
    ];
    expect(options.message.allowedMentions.everyone).toBe(false);
    expect(options.message.allowedMentions.roles).toBe(false);
  });

  test("create_forum_post fails on an unknown tag without starting a post", async () => {
    const start = recorder({ id: POST, name: "hello", appliedTags: [] });
    const ctx = fakeContext({
      guilds: { getChannels: async () => [forum] },
      channels: { startThreadInThreadOnlyChannel: start },
    });
    const result = await callTool(forumTools, ctx, "create_forum_post", {
      channelId: FORUM,
      title: "hello",
      message: "first",
      tagIds: "Missing",
    });
    expect(result.isError).toBe(true);
    expect(start.calls).toHaveLength(0);
  });

  test("create_forum_post fails on more than five tags", async () => {
    const start = recorder({ id: POST, name: "hello", appliedTags: [] });
    const many = Array.from({ length: 6 }, (_, i) => `${i + 1}`);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [
          {
            ...forum,
            availableTags: many.map((id) => ({ id, name: `t${id}` })),
          },
        ],
      },
      channels: { startThreadInThreadOnlyChannel: start },
    });
    const result = await callTool(forumTools, ctx, "create_forum_post", {
      channelId: FORUM,
      title: "hello",
      message: "first",
      tagIds: many.join(","),
    });
    expect(result.isError).toBe(true);
    expect(start.calls).toHaveLength(0);
  });

  test("modify_forum_post pins a post and keeps its other channel flags", async () => {
    const edit = recorder({ id: POST, name: "hello" });
    const ctx = fakeContext({
      guilds: { getChannels: async () => [forum] },
      channels: {
        get: async () => post(ChannelFlags.REQUIRE_TAG),
        edit,
      },
    });
    const result = await callTool(forumTools, ctx, "modify_forum_post", {
      postId: POST,
      pinned: true,
    });
    expect(result.isError).toBe(false);
    const [, options] = edit.calls[0] as [string, { flags: number }];
    expect(options.flags).toBe(ChannelFlags.REQUIRE_TAG | ChannelFlags.PINNED);
  });
});
