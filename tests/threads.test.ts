import { describe, expect, test } from "bun:test";
import { ChannelTypes } from "oceanic.js";
import { threadTools } from "../src/tools/threads.ts";
import { callTool, fakeContext, recorder, restError } from "./harness.ts";

const GUILD = "111111111111111111";
const PARENT = "300000000000000001";
const THREAD = "600000000000000001";
const MESSAGE = "200000000000000009";

const fakeThread = (overrides: Record<string, unknown> = {}) => ({
  id: THREAD,
  name: "release notes",
  type: ChannelTypes.PUBLIC_THREAD,
  parentID: PARENT,
  messageCount: 3,
  createdAt: new Date("2024-05-06T07:08:09.000Z"),
  threadMetadata: { archived: false, locked: false },
  ...overrides,
});

const OLD = "600000000000000002";
const archivedThread = fakeThread({
  id: OLD,
  name: "old notes",
  threadMetadata: { archived: true, locked: true },
});
const parentChannels = [
  { id: PARENT, name: "announcements", type: ChannelTypes.GUILD_TEXT },
];

describe("list_threads", () => {
  test("lists each thread with its parent ID and parent name", async () => {
    const getChannels = recorder([
      { id: PARENT, name: "announcements", type: ChannelTypes.GUILD_TEXT },
      {
        id: "300000000000000002",
        name: "general",
        type: ChannelTypes.GUILD_TEXT,
      },
    ]);
    const ctx = fakeContext({
      guilds: {
        getActiveThreads: async () => ({
          members: [],
          threads: [fakeThread()],
        }),
        getChannels,
      },
    });

    const result = await callTool(threadTools, ctx, "list_threads", {});

    expect(result.isError).toBe(false);
    expect(getChannels.calls).toEqual([[GUILD]]);
    expect(result.data).toEqual({
      count: 1,
      threads: [
        {
          id: THREAD,
          name: "release notes",
          type: "public_thread",
          parentId: PARENT,
          parentName: "announcements",
          archived: false,
          locked: false,
          createdAt: "2024-05-06T07:08:09.000Z",
          messageCount: 3,
        },
      ],
    });
  });

  test("with a channel ID merges active and archived threads without duplicates", async () => {
    const getPublicArchivedThreads = recorder({
      hasMore: false,
      members: [],
      threads: [fakeThread(), archivedThread],
    });
    const ctx = fakeContext({
      guilds: {
        getActiveThreads: async () => ({
          members: [],
          threads: [
            fakeThread(),
            fakeThread({
              id: "600000000000000003",
              parentID: "300000000000000002",
            }),
          ],
        }),
        getChannels: async () => parentChannels,
      },
      channels: { getPublicArchivedThreads },
    });

    const result = await callTool(threadTools, ctx, "list_threads", {
      channelId: PARENT,
    });

    expect(result.isError).toBe(false);
    expect(getPublicArchivedThreads.calls).toEqual([[PARENT, { limit: 100 }]]);
    const data = result.data as {
      count: number;
      note?: string;
      threads: Array<{ id: string; archived: boolean; locked: boolean }>;
    };
    expect(data.count).toBe(2);
    expect(data.note).toBeUndefined();
    expect(data.threads.map((t) => t.id)).toEqual([THREAD, OLD]);
    expect(data.threads[1]).toMatchObject({ archived: true, locked: true });
  });

  test("notes when Discord has more archived threads than one page", async () => {
    const ctx = fakeContext({
      guilds: {
        getActiveThreads: async () => ({ members: [], threads: [] }),
        getChannels: async () => parentChannels,
      },
      channels: {
        getPublicArchivedThreads: async () => ({
          hasMore: true,
          members: [],
          threads: [archivedThread],
        }),
      },
    });

    const result = await callTool(threadTools, ctx, "list_threads", {
      channelId: PARENT,
    });

    expect((result.data as { note?: string }).note).toBeDefined();
  });

  test("a 403 on archived threads still returns the active ones with a note", async () => {
    const ctx = fakeContext({
      guilds: {
        getActiveThreads: async () => ({
          members: [],
          threads: [fakeThread()],
        }),
        getChannels: async () => parentChannels,
      },
      channels: {
        getPublicArchivedThreads: async () => {
          throw restError(403, 50013);
        },
      },
    });

    const result = await callTool(threadTools, ctx, "list_threads", {
      channelId: PARENT,
    });

    expect(result.isError).toBe(false);
    const data = result.data as { count: number; note: string };
    expect(data.count).toBe(1);
    expect(data.note).toContain("Archived threads were skipped");
    expect(data.note).toContain("403");
  });

  test("gives a null parent name when the parent is not in the server", async () => {
    const ctx = fakeContext({
      guilds: {
        getActiveThreads: async () => ({
          members: [],
          threads: [fakeThread({ parentID: "300000000000000999" })],
        }),
        getChannels: async () => [],
      },
    });

    const result = await callTool(threadTools, ctx, "list_threads", {});

    const threads = (result.data as { threads: Array<{ parentName: unknown }> })
      .threads;
    expect(threads[0]?.parentName).toBeUndefined();
  });
});

describe("create_thread_from_message", () => {
  test("starts a thread from the message with the name and archive duration", async () => {
    const startThreadFromMessage = recorder(fakeThread({ name: "discussion" }));
    const ctx = fakeContext({
      channels: {
        get: async () => ({
          id: PARENT,
          name: "announcements",
          type: ChannelTypes.GUILD_TEXT,
        }),
        startThreadFromMessage,
      },
    });

    const result = await callTool(
      threadTools,
      ctx,
      "create_thread_from_message",
      {
        channelId: PARENT,
        messageId: MESSAGE,
        name: "discussion",
        autoArchiveDuration: 1440,
      },
    );

    expect(result.isError).toBe(false);
    expect(startThreadFromMessage.calls).toEqual([
      [PARENT, MESSAGE, { name: "discussion", autoArchiveDuration: 1440 }],
    ]);
    expect(result.data).toMatchObject({
      id: THREAD,
      name: "discussion",
      parentId: PARENT,
      parentName: "announcements",
    });
  });

  test("rejects a voice channel and starts no thread", async () => {
    const startThreadFromMessage = recorder(fakeThread());
    const ctx = fakeContext({
      channels: {
        get: async () => ({ id: PARENT, type: ChannelTypes.GUILD_VOICE }),
        startThreadFromMessage,
      },
    });

    const result = await callTool(
      threadTools,
      ctx,
      "create_thread_from_message",
      { channelId: PARENT, messageId: MESSAGE, name: "discussion" },
    );

    expect(result.isError).toBe(true);
    expect(startThreadFromMessage.calls).toHaveLength(0);
  });

  test("rejects an archive duration that Discord does not offer", async () => {
    const startThreadFromMessage = recorder(fakeThread());
    const ctx = fakeContext({
      channels: {
        get: async () => ({ id: PARENT, type: ChannelTypes.GUILD_TEXT }),
        startThreadFromMessage,
      },
    });

    const result = await callTool(
      threadTools,
      ctx,
      "create_thread_from_message",
      {
        channelId: PARENT,
        messageId: MESSAGE,
        name: "discussion",
        autoArchiveDuration: 30,
      },
    );

    expect(result.isError).toBe(true);
    expect(startThreadFromMessage.calls).toHaveLength(0);
  });
});
