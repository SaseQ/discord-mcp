import { describe, expect, test } from "bun:test";
import { ChannelTypes, MessageTypes } from "oceanic.js";
import { messageTools } from "../src/tools/messages.ts";
import {
  callTool,
  type FakeRest,
  fakeContext,
  recorder,
  restError,
} from "./harness.ts";
import { fakeAttachment, fakeMessage } from "./messages-harness.ts";

const CHANNEL = "300000000000000001";
const MESSAGE = "200000000000000009";

const idAfter = (n: number) => String(BigInt(MESSAGE) + BigInt(n));

/** A channel of `count` messages newer than MESSAGE, which pages as Discord does: at most 100, newest first, also with `after`. */
const discordHistory = (
  count: number,
  make: (n: number) => Record<string, unknown> = () => ({}),
) => {
  const all = Array.from({ length: count }, (_, i) =>
    fakeMessage({ id: idAfter(i + 1), ...make(i + 1) }),
  );
  const limits: number[] = [];
  const getMessages = async (
    _channelId: string,
    options: { limit: number; before?: string; after?: string },
  ) => {
    limits.push(options.limit);
    const { before, after } = options;
    const limit = Math.min(options.limit, 100);
    const page =
      after !== undefined
        ? all.filter((m) => BigInt(m.id) > BigInt(after)).slice(0, limit)
        : all
            .filter(
              (m) => before === undefined || BigInt(m.id) < BigInt(before),
            )
            .slice(-limit);
    return page.reverse();
  };
  return Object.assign(getMessages, { limits });
};

/** Channel routes that answer as a text channel, with the given message routes. */
const textChannel = (channels: Record<string, unknown> = {}): FakeRest => ({
  channels: {
    get: async () => ({
      id: CHANNEL,
      type: ChannelTypes.GUILD_TEXT,
      guildID: "111111111111111111",
    }),
    ...channels,
  },
});

describe("send_message", () => {
  test("the link names the guild of the channel, since REST messages have no guild_id", async () => {
    const createMessage = recorder(fakeMessage({ guildID: null }));
    const ctx = fakeContext(textChannel({ createMessage }));

    const result = await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
      message: "hi",
    });

    expect(result.data).toMatchObject({
      jumpUrl: `https://discord.com/channels/111111111111111111/${CHANNEL}/200000000000000001`,
    });
  });

  test("a reply references the replied message and fails when it is gone by default", async () => {
    const createMessage = recorder(fakeMessage());
    const ctx = fakeContext(textChannel({ createMessage }));

    const result = await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
      message: "hi",
      replyToMessageId: MESSAGE,
    });

    expect(result.isError).toBe(false);
    const [channelId, options] = createMessage.calls[0] as [
      string,
      { messageReference: unknown },
    ];
    expect(channelId).toBe(CHANNEL);
    expect(options.messageReference).toEqual({
      channelID: CHANNEL,
      messageID: MESSAGE,
      failIfNotExists: true,
    });
  });

  test("a reply passes failIfNotExists false when the caller asks for it", async () => {
    const createMessage = recorder(fakeMessage());
    const ctx = fakeContext(textChannel({ createMessage }));

    await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
      message: "hi",
      replyToMessageId: MESSAGE,
      failIfNotExists: false,
    });

    const [, options] = createMessage.calls[0] as [
      string,
      { messageReference: { failIfNotExists: boolean } },
    ];
    expect(options.messageReference.failIfNotExists).toBe(false);
  });

  test("is an error with no text, embeds, or files, and sends nothing", async () => {
    const createMessage = recorder(fakeMessage());
    const ctx = fakeContext(textChannel({ createMessage }));

    const result = await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
    });

    expect(result.isError).toBe(true);
    expect(createMessage.calls).toHaveLength(0);
  });

  test("does not ping @everyone or roles unless allowEveryone is true", async () => {
    const createMessage = recorder(fakeMessage());
    const ctx = fakeContext(textChannel({ createMessage }));

    await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
      message: "@everyone hi",
    });
    await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
      message: "@everyone hi",
      allowEveryone: true,
    });

    const mentionsOf = (call: unknown[]) =>
      (call[1] as { allowedMentions: { everyone: boolean; roles: boolean } })
        .allowedMentions;
    expect(mentionsOf(createMessage.calls[0] ?? [])).toMatchObject({
      everyone: false,
      roles: false,
    });
    expect(mentionsOf(createMessage.calls[1] ?? [])).toMatchObject({
      everyone: true,
      roles: true,
    });
  });
});

describe("send_message files", () => {
  test("sends the files that the loader returned", async () => {
    const file = { name: "notes.txt", contents: Buffer.from("hello") };
    const load = recorder(file);
    const createMessage = recorder(fakeMessage());
    const ctx = fakeContext(textChannel({ createMessage }), {
      files: { load },
    });

    const result = await callTool(messageTools, ctx, "send_message", {
      channelId: CHANNEL,
      files: [{ filePath: "/srv/files/notes.txt" }],
    });

    expect(result.isError).toBe(false);
    expect(load.calls[0]?.[0]).toEqual({ filePath: "/srv/files/notes.txt" });
    const [, options] = createMessage.calls[0] as [
      string,
      { files: unknown[] },
    ];
    expect(options.files).toEqual([file]);
  });
});

describe("read_messages", () => {
  test.each([
    ["before", "after"],
    ["before", "around"],
    ["after", "around"],
  ])("rejects %s together with %s, and reads nothing", async (a, b) => {
    const getMessages = recorder([fakeMessage()]);
    const ctx = fakeContext(textChannel({ getMessages }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
      [a]: MESSAGE,
      [b]: MESSAGE,
    });

    expect(result.isError).toBe(true);
    expect(getMessages.calls).toHaveLength(0);
  });

  test("passes one cursor and the count to Discord", async () => {
    const getMessages = recorder([fakeMessage()]);
    const ctx = fakeContext(textChannel({ getMessages }));

    await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
      count: 5,
      before: MESSAGE,
    });

    expect(getMessages.calls[0]).toEqual([
      CHANNEL,
      { limit: 5, before: MESSAGE },
    ]);
  });

  test("returns each message in the item shape", async () => {
    const message = fakeMessage({
      attachments: { toArray: () => [fakeAttachment()] },
      type: MessageTypes.REPLY,
      messageReference: { messageID: MESSAGE },
    });
    const ctx = fakeContext(textChannel({ getMessages: recorder([message]) }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
    });

    expect(result.isError).toBe(false);
    const data = result.data as {
      channelId: string;
      count: number;
      messages: Array<Record<string, unknown>>;
    };
    expect(data.count).toBe(1);
    const item = data.messages[0];
    expect(item).toMatchObject({
      id: "200000000000000001",
      channelId: CHANNEL,
      content: "hello",
      author: {
        id: "400000000000000001",
        username: "alex",
        displayName: "Alex",
      },
      attachments: [
        {
          id: "500000000000000001",
          filename: "notes.txt",
          size: 12,
          contentType: "text/plain",
          url: "https://cdn.discordapp.com/attachments/notes.txt",
        },
      ],
      replyTo: MESSAGE,
    });
    expect(item).not.toHaveProperty("jumpUrl");
  });

  test("contains scans past the count, filters without case, and reports a full scan", async () => {
    const getMessages = recorder([
      fakeMessage({ id: "200000000000000003", content: "Release v2" }),
      fakeMessage({ id: "200000000000000002", content: "lunch" }),
    ]);
    const ctx = fakeContext(textChannel({ getMessages }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
      contains: "release",
      scanLimit: 50,
    });

    expect(getMessages.calls[0]).toEqual([CHANNEL, { limit: 51 }]);
    expect(result.data).toMatchObject({
      scanned: 2,
      complete: true,
      count: 1,
      messages: [{ id: "200000000000000003" }],
    });
  });

  test("contains reports an incomplete scan when older messages remain", async () => {
    const page = Array.from({ length: 3 }, (_, i) =>
      fakeMessage({ id: `20000000000000000${i}` }),
    );
    const ctx = fakeContext(textChannel({ getMessages: recorder(page) }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
      contains: "hello",
      scanLimit: 2,
    });

    expect(result.data).toMatchObject({ scanned: 2, complete: false });
  });

  test("contains pages back with before past 100 messages, and keeps the newest", async () => {
    const history = discordHistory(300);
    const ctx = fakeContext(textChannel({ getMessages: history }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
      contains: "hello",
      scanLimit: 150,
    });

    const data = result.data as {
      scanned: number;
      complete: boolean;
      messages: Array<{ id: string }>;
    };
    expect(history.limits.every((l) => l <= 100)).toBe(true);
    expect(data.scanned).toBe(150);
    expect(data.complete).toBe(false);
    expect(data.messages[0]?.id).toBe(idAfter(300));
  });

  test("scanLimit without contains is an error, and reads nothing", async () => {
    const getMessages = recorder([fakeMessage()]);
    const ctx = fakeContext(textChannel({ getMessages }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
      scanLimit: 10,
    });

    expect(result.isError).toBe(true);
    expect(getMessages.calls).toHaveLength(0);
  });
});

describe("replyTo", () => {
  test("is left out for a forward, which has a message reference but is no reply", async () => {
    const forward = fakeMessage({ messageReference: { messageID: MESSAGE } });
    const ctx = fakeContext(textChannel({ getMessages: recorder([forward]) }));

    const result = await callTool(messageTools, ctx, "read_messages", {
      channelId: CHANNEL,
    });

    const data = result.data as { messages: Array<Record<string, unknown>> };
    expect(data.messages[0]?.["replyTo"]).toBeUndefined();
  });
});

describe("get_message", () => {
  test("attachmentId keeps only that attachment, and fails when it is not on the message", async () => {
    const message = fakeMessage({
      attachments: {
        toArray: () => [
          fakeAttachment(),
          fakeAttachment({ id: "500000000000000002", filename: "b.png" }),
        ],
      },
    });
    const ctx = fakeContext(textChannel({ getMessage: recorder(message) }));
    const ask = (attachmentId: string) =>
      callTool(messageTools, ctx, "get_message", {
        channelId: CHANNEL,
        messageId: "200000000000000001",
        attachmentId,
      });

    const one = await ask("500000000000000002");
    expect(one.isError).toBe(false);
    expect(
      (one.data as { attachments: { id: string }[] }).attachments.map(
        (a) => a.id,
      ),
    ).toEqual(["500000000000000002"]);
    expect((await ask("500000000000000099")).isError).toBe(true);
  });

  test("returns the message with its link and the message it replies to", async () => {
    const replied = fakeMessage({ id: MESSAGE, content: "question" });
    const message = fakeMessage({
      type: MessageTypes.REPLY,
      messageReference: { messageID: MESSAGE },
      referencedMessage: replied,
    });
    const ctx = fakeContext(textChannel({ getMessage: recorder(message) }));

    const result = await callTool(messageTools, ctx, "get_message", {
      channelId: CHANNEL,
      messageId: "200000000000000001",
    });

    expect(result.isError).toBe(false);
    expect(result.data).toMatchObject({
      id: "200000000000000001",
      channelId: CHANNEL,
      jumpUrl: `https://discord.com/channels/111111111111111111/${CHANNEL}/200000000000000001`,
      replyTo: MESSAGE,
      repliedMessage: { id: MESSAGE, content: "question" },
    });
  });

  test("includeReplies scans the newer messages and keeps only replies to it, oldest first", async () => {
    const target = fakeMessage({ id: MESSAGE });
    const reply = (id: string, to: string) =>
      fakeMessage({
        id,
        type: MessageTypes.REPLY,
        messageReference: { messageID: to },
      });
    const getMessages = recorder([
      reply("200000000000000030", MESSAGE),
      reply("200000000000000020", "200000000000000001"),
      fakeMessage({
        id: "200000000000000015",
        messageReference: { messageID: MESSAGE },
      }),
      reply("200000000000000010", MESSAGE),
    ]);
    const ctx = fakeContext(
      textChannel({ getMessage: recorder(target), getMessages }),
    );

    const result = await callTool(messageTools, ctx, "get_message", {
      channelId: CHANNEL,
      messageId: MESSAGE,
      includeReplies: true,
    });

    expect(getMessages.calls[0]).toEqual([
      CHANNEL,
      { limit: 100, after: MESSAGE },
    ]);
    const data = result.data as {
      replies: Array<{ id: string }>;
      repliesScanned: number;
      repliesComplete: boolean;
    };
    expect(data.replies.map((r) => r.id)).toEqual([
      "200000000000000010",
      "200000000000000030",
    ]);
    expect(data.repliesScanned).toBe(4);
    expect(data.repliesComplete).toBe(true);
  });

  test("includeReplies pages forward past 100 messages, once each", async () => {
    const history = discordHistory(250, (n) =>
      n % 50 === 0
        ? { type: MessageTypes.REPLY, messageReference: { messageID: MESSAGE } }
        : {},
    );
    const ctx = fakeContext(
      textChannel({
        getMessage: recorder(fakeMessage({ id: MESSAGE })),
        getMessages: history,
      }),
    );

    const result = await callTool(messageTools, ctx, "get_message", {
      channelId: CHANNEL,
      messageId: MESSAGE,
      includeReplies: true,
    });

    expect(history.limits.every((l) => l <= 100)).toBe(true);
    expect(result.data).toMatchObject({
      replies: [50, 100, 150, 200, 250].map((n) => ({ id: idAfter(n) })),
      repliesScanned: 250,
      repliesComplete: true,
    });
  });
});

describe("react", () => {
  test.each([
    ["add", "createReaction"],
    ["remove", "deleteReaction"],
  ])("%s calls %s with the emoji", async (action, route) => {
    const call = recorder(undefined);
    const ctx = fakeContext(textChannel({ [route]: call }));

    const result = await callTool(messageTools, ctx, "react", {
      channelId: CHANNEL,
      messageId: MESSAGE,
      emoji: "👍",
      action,
    });

    expect(result.isError).toBe(false);
    expect(call.calls[0]).toEqual([CHANNEL, MESSAGE, "👍"]);
  });
});

describe("search_guild_messages", () => {
  test("is an error when Discord refuses the search with 403", async () => {
    const ctx = fakeContext({
      guilds: {
        searchMessages: async () => {
          throw restError(403, 0, "GET");
        },
      },
    });

    const result = await callTool(messageTools, ctx, "search_guild_messages", {
      content: "hello",
    });

    expect(result.isError).toBe(true);
  });
});
