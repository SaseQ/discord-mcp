import { describe, expect, test } from "bun:test";
import { MessageTypes } from "oceanic.js";
import { dmTools } from "../src/tools/dms.ts";
import { callTool, fakeContext, recorder } from "./harness.ts";
import { fakeMessage } from "./messages-harness.ts";

const USER = "400000000000000001";
const DM = "700000000000000001";
const MESSAGE = "200000000000000009";

/** A context whose DM with the user is the given channel. */
const dmContext = (channels: Record<string, unknown>) =>
  fakeContext({
    channels: {
      createDM: recorder({ id: DM, type: 1 }),
      ...channels,
    },
  });

describe("send_private_message", () => {
  test("sends to the DM channel that createDM opened for the user", async () => {
    const createDM = recorder({ id: DM, type: 1 });
    const createMessage = recorder(
      fakeMessage({ channelID: DM, guildID: undefined }),
    );
    const ctx = fakeContext({ channels: { createDM, createMessage } });

    const result = await callTool(dmTools, ctx, "send_private_message", {
      userId: USER,
      message: "hi",
    });

    expect(result.isError).toBe(false);
    expect(createDM.calls).toEqual([[USER]]);
    expect(createMessage.calls[0]?.[0]).toBe(DM);
    expect(result.data).toMatchObject({
      channelId: DM,
      jumpUrl: `https://discord.com/channels/@me/${DM}/200000000000000001`,
    });
  });

  test("is an error with no text, embeds, or files, and sends nothing", async () => {
    const createMessage = recorder(fakeMessage());
    const ctx = dmContext({ createMessage });

    const result = await callTool(dmTools, ctx, "send_private_message", {
      userId: USER,
    });

    expect(result.isError).toBe(true);
    expect(createMessage.calls).toHaveLength(0);
  });
});

describe("edit_private_message", () => {
  test("edits the message in the DM channel", async () => {
    const editMessage = recorder(fakeMessage({ content: "fixed" }));
    const ctx = dmContext({ editMessage });

    const result = await callTool(dmTools, ctx, "edit_private_message", {
      userId: USER,
      messageId: MESSAGE,
      newMessage: "fixed",
    });

    expect(result.isError).toBe(false);
    expect(editMessage.calls[0]?.slice(0, 2)).toEqual([DM, MESSAGE]);
    expect(result.data).toMatchObject({ content: "fixed" });
  });
});

describe("delete_private_message", () => {
  test("deletes the message from the DM channel", async () => {
    const deleteMessage = recorder(undefined);
    const ctx = dmContext({ deleteMessage });

    const result = await callTool(dmTools, ctx, "delete_private_message", {
      userId: USER,
      messageId: MESSAGE,
    });

    expect(result.data).toEqual({
      deleted: true,
      channelId: DM,
      messageId: MESSAGE,
    });
    expect(deleteMessage.calls[0]).toEqual([DM, MESSAGE]);
  });

  test("is the only destructive tool of the DM group", () => {
    const destructive = dmTools
      .filter((tool) => tool.access === "destructive")
      .map((tool) => tool.name);
    expect(destructive).toEqual(["delete_private_message"]);
  });
});

describe("read_private_messages", () => {
  test("returns the DM messages in the read_messages item shape", async () => {
    const getMessages = recorder([
      fakeMessage({
        channelID: DM,
        guildID: undefined,
        type: MessageTypes.REPLY,
        messageReference: { messageID: MESSAGE },
      }),
    ]);
    const ctx = dmContext({ getMessages });

    const result = await callTool(dmTools, ctx, "read_private_messages", {
      userId: USER,
      count: 10,
    });

    expect(result.isError).toBe(false);
    expect(getMessages.calls[0]).toEqual([DM, { limit: 10 }]);
    expect(result.data).toMatchObject({
      channelId: DM,
      count: 1,
      messages: [
        {
          id: "200000000000000001",
          channelId: DM,
          author: {
            id: "400000000000000001",
            username: "alex",
            displayName: "Alex",
          },
          replyTo: MESSAGE,
        },
      ],
    });
  });

  test("rejects two cursors and reads nothing", async () => {
    const getMessages = recorder([fakeMessage()]);
    const ctx = dmContext({ getMessages });

    const result = await callTool(dmTools, ctx, "read_private_messages", {
      userId: USER,
      before: MESSAGE,
      after: MESSAGE,
    });

    expect(result.isError).toBe(true);
    expect(getMessages.calls).toHaveLength(0);
  });
});
