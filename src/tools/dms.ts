import { z } from "zod";
import type { Context } from "../context.ts";
import { defineMergedTool, defineTool, type Tool } from "../tool.ts";
import {
  embedSchema,
  fileSource,
  historyOptions,
  loadFiles,
  MAX_CONTENT_LENGTH,
  messageFields,
  toLinkedMessage,
  toMessageItem,
} from "./format.ts";
import {
  afterParam,
  allowEveryoneParam,
  aroundParam,
  beforeParam,
  countParam,
  messageIdParam,
} from "./messages.ts";
import { snowflake } from "./schemas.ts";

const userIdParam = snowflake("User ID").describe("User of the DM");

/** Opens, or reuses, the DM channel with a user. */
const dmChannelOf = (ctx: Context, userId: string) =>
  ctx.client.rest.channels.createDM(userId);

const messageText = z.string().min(1).max(MAX_CONTENT_LENGTH).optional();

export const dmTools: Tool[] = [
  defineMergedTool({
    name: "send_private_message",
    title: "Send private message",
    description:
      "Send a direct message (DM) to a user: text or embeds. @everyone, @here, and roles do not ping unless allowEveryone.",
    group: "dms",
    parts: [
      {
        name: "send_private_message",
        group: "dms",
        access: "write",
        value: "text",
        note: "",
      },
      {
        name: "send_private_file",
        group: "dms",
        access: "write",
        value: "files",
        params: ["files"],
        note: "files uploads files.",
      },
    ],
    input: z.object({
      userId: userIdParam,
      message: messageText,
      embeds: z.array(embedSchema).max(10).optional(),
      files: z.array(fileSource).max(10).optional(),
      allowEveryone: allowEveryoneParam,
    }),
    run: async (args, ctx) => {
      const dm = await dmChannelOf(ctx, args.userId);
      const files = await loadFiles(ctx, args.files);
      const sent = await ctx.client.rest.channels.createMessage(
        dm.id,
        messageFields({
          content: args.message,
          embeds: args.embeds,
          files,
          allowEveryone: args.allowEveryone,
        }),
      );
      return toLinkedMessage(sent);
    },
  }),
  defineTool({
    name: "edit_private_message",
    title: "Edit private message",
    description:
      "Edit a direct message that the bot sent to a user: new text, new embeds, or both. Embeds replace the old ones.",
    group: "dms",
    access: "write",
    input: z.object({
      userId: userIdParam,
      messageId: messageIdParam,
      newMessage: messageText,
      embeds: z.array(embedSchema).max(10).optional(),
      allowEveryone: allowEveryoneParam,
    }),
    run: async (args, ctx) => {
      const dm = await dmChannelOf(ctx, args.userId);
      const edited = await ctx.client.rest.channels.editMessage(
        dm.id,
        args.messageId,
        messageFields({
          content: args.newMessage,
          embeds: args.embeds,
          allowEveryone: args.allowEveryone,
        }),
      );
      return toLinkedMessage(edited);
    },
  }),
  defineTool({
    name: "delete_private_message",
    title: "Delete private message",
    description: "Delete a direct message from the DM with a user.",
    group: "dms",
    access: "destructive",
    input: z.object({
      userId: userIdParam,
      messageId: messageIdParam,
    }),
    run: async (args, ctx) => {
      const dm = await dmChannelOf(ctx, args.userId);
      await ctx.client.rest.channels.deleteMessage(dm.id, args.messageId);
      return {
        deleted: true,
        channelId: dm.id,
        messageId: args.messageId,
      };
    },
  }),
  defineTool({
    name: "read_private_messages",
    title: "Read private messages",
    description:
      "Read the direct messages (DM) with a user, newest first. Page with one of before, after, or around.",
    group: "dms",
    access: "read",
    input: z.object({
      userId: userIdParam,
      count: countParam,
      before: beforeParam,
      after: afterParam,
      around: aroundParam,
    }),
    run: async (args, ctx) => {
      const options = historyOptions(args);
      const dm = await dmChannelOf(ctx, args.userId);
      const messages = await ctx.client.rest.channels.getMessages(
        dm.id,
        options,
      );
      return {
        channelId: dm.id,
        count: messages.length,
        messages: messages.map(toMessageItem),
      };
    },
  }),
];
