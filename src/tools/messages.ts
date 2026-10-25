import { DiscordRESTError, type Message } from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineMergedTool, defineTool, type Tool, ToolError } from "../tool.ts";
import {
  definedOnly,
  embedSchema,
  fileSource,
  historyOptions,
  loadFiles,
  MAX_CONTENT_LENGTH,
  MAX_READ_LIMIT,
  messageFields,
  repliedTo,
  requireMessageChannel,
  toLinkedMessage,
  toMessageItem,
} from "./format.ts";
import { guildId, snowflake } from "./schemas.ts";

const channelIdParam = snowflake("Channel ID");
export const messageIdParam = snowflake("Message ID");
export const countParam = z
  .number()
  .int()
  .min(1)
  .max(MAX_READ_LIMIT)
  .optional()
  .describe(`Default ${MAX_READ_LIMIT}`);
export const beforeParam = snowflake("before").optional();
export const afterParam = snowflake("after").optional();
export const aroundParam = snowflake("around").optional();
export const allowEveryoneParam = z
  .boolean()
  .optional()
  .describe("Let @everyone, @here, and roles ping");

const DEFAULT_SCAN = 500;
const scanParam = z
  .number()
  .int()
  .min(1)
  .max(1000)
  .optional()
  .describe(`Messages to scan, default ${DEFAULT_SCAN}`);

/** The Discord search filters that Oceanic types. */
const SEARCH_HAS = [
  "link",
  "embed",
  "file",
  "video",
  "image",
  "sound",
] as const;

/** Maps an index that is not ready, or a refused search, to an error that tells the caller what to do. */
const searchFailure = (error: unknown): unknown => {
  if (error instanceof DiscordRESTError && error.status === 403) {
    return new ToolError(
      "Discord refused the search with 403 Forbidden. Check that the bot can view the channels that the search covers, and that the server allows message search.",
    );
  }
  if (
    error instanceof Error &&
    error.message.includes("index not being available")
  ) {
    return new ToolError(
      "Discord's message search index for this server is not ready yet. Wait a few minutes, then run the search again.",
    );
  }
  return error;
};

const byIdAscending = (a: Message, b: Message) =>
  BigInt(a.id) < BigInt(b.id) ? -1 : 1;

/** Reads up to `limit` messages from a cursor, newest first, plus one that tells whether the scan reached the end of the history. */
const scanHistory = async (
  ctx: Context,
  channelId: string,
  cursor: { before?: string | undefined; after?: string | undefined },
  limit: number,
) => {
  // Oceanic pages `after` from the oldest message of each page, which Discord returns newest first, so the scan pages itself.
  const seen = new Map<string, Message>();
  let { before, after } = cursor;
  while (seen.size <= limit) {
    const want = Math.min(MAX_READ_LIMIT, limit + 1 - seen.size);
    const page = await ctx.client.rest.channels.getMessages(
      channelId,
      definedOnly({ limit: want, before, after }),
    );
    const size = seen.size;
    for (const message of page) seen.set(message.id, message);
    if (page.length < want || seen.size === size) break;
    const sorted = [...page].sort(byIdAscending);
    if (after !== undefined) after = sorted.at(-1)?.id;
    else before = sorted[0]?.id;
  }
  const ascending = [...seen.values()].sort(byIdAscending);
  const kept =
    cursor.after === undefined
      ? ascending.slice(-limit)
      : ascending.slice(0, limit);
  return { messages: kept.reverse(), complete: seen.size <= limit };
};

export const messageTools: Tool[] = [
  defineMergedTool({
    name: "send_message",
    title: "Send message",
    description:
      "Send a message to a channel or thread: text, embeds, or a reply. @everyone, @here, and roles do not ping unless allowEveryone.",
    group: "messages",
    parts: [
      {
        name: "send_message",
        group: "messages",
        access: "write",
        value: "text",
        note: "",
      },
      {
        name: "send_file",
        group: "messages",
        access: "write",
        value: "files",
        params: ["files"],
        note: "files uploads files.",
      },
    ],
    input: z.object({
      channelId: channelIdParam,
      message: z.string().min(1).max(MAX_CONTENT_LENGTH).optional(),
      replyToMessageId: snowflake("replyToMessageId").optional(),
      failIfNotExists: z
        .boolean()
        .optional()
        .describe("Fail the reply if its message is gone. Default true"),
      embeds: z.array(embedSchema).max(10).optional(),
      files: z.array(fileSource).max(10).optional(),
      allowEveryone: allowEveryoneParam,
    }),
    run: async (args, ctx) => {
      if (args.failIfNotExists !== undefined && !args.replyToMessageId) {
        throw new ToolError("failIfNotExists needs replyToMessageId");
      }
      const channel = await requireMessageChannel(ctx, args.channelId);
      const files = await loadFiles(ctx, args.files);
      const options = messageFields({
        content: args.message,
        embeds: args.embeds,
        files,
        allowEveryone: args.allowEveryone,
      });
      const sent = await ctx.client.rest.channels.createMessage(
        args.channelId,
        definedOnly({
          ...options,
          messageReference: args.replyToMessageId
            ? {
                channelID: args.channelId,
                messageID: args.replyToMessageId,
                failIfNotExists: args.failIfNotExists ?? true,
              }
            : undefined,
        }),
      );
      return toLinkedMessage(sent, channel.guildID);
    },
  }),
  defineTool({
    name: "edit_message",
    title: "Edit message",
    description:
      "Edit a message in a channel or thread: new text, new embeds, or both. Embeds replace the old ones.",
    group: "messages",
    access: "write",
    input: z.object({
      channelId: channelIdParam,
      messageId: messageIdParam,
      newMessage: z.string().min(1).max(MAX_CONTENT_LENGTH).optional(),
      embeds: z.array(embedSchema).max(10).optional(),
      allowEveryone: allowEveryoneParam,
    }),
    run: async (args, ctx) => {
      if (!args.newMessage && !args.embeds) {
        throw new ToolError("Give newMessage, embeds, or both");
      }
      const channel = await requireMessageChannel(ctx, args.channelId);
      const edited = await ctx.client.rest.channels.editMessage(
        args.channelId,
        args.messageId,
        messageFields({
          content: args.newMessage,
          embeds: args.embeds,
          allowEveryone: args.allowEveryone,
        }),
      );
      return toLinkedMessage(edited, channel.guildID);
    },
  }),
  defineTool({
    name: "delete_message",
    title: "Delete message",
    description: "Delete a message from a channel or thread.",
    group: "messages",
    access: "destructive",
    input: z.object({
      channelId: channelIdParam,
      messageId: messageIdParam,
    }),
    run: async (args, ctx) => {
      await requireMessageChannel(ctx, args.channelId);
      await ctx.client.rest.channels.deleteMessage(
        args.channelId,
        args.messageId,
      );
      return {
        deleted: true,
        channelId: args.channelId,
        messageId: args.messageId,
      };
    },
  }),
  defineTool({
    name: "read_messages",
    title: "Read messages",
    description:
      "Read or search the history of a channel or thread, newest first. Page with one of before, after, or around. contains finds text by scanning up to scanLimit messages.",
    group: "messages",
    access: "read",
    input: z.object({
      channelId: channelIdParam,
      count: countParam,
      before: beforeParam,
      after: afterParam,
      around: aroundParam,
      contains: z
        .string()
        .min(1)
        .optional()
        .describe("Return only messages with this text, ignoring case"),
      scanLimit: scanParam,
    }),
    run: async (args, ctx) => {
      const options = historyOptions(args);
      if (args.contains === undefined) {
        if (args.scanLimit !== undefined)
          throw new ToolError("scanLimit needs contains");
        await requireMessageChannel(ctx, args.channelId);
        const messages = await ctx.client.rest.channels.getMessages(
          args.channelId,
          options,
        );
        return {
          channelId: args.channelId,
          count: messages.length,
          messages: messages.map(toMessageItem),
        };
      }
      if (args.around)
        throw new ToolError("contains scans from before or after, not around");
      await requireMessageChannel(ctx, args.channelId);
      const { messages, complete } = await scanHistory(
        ctx,
        args.channelId,
        { before: args.before, after: args.after },
        args.scanLimit ?? DEFAULT_SCAN,
      );
      const needle = args.contains.toLowerCase();
      const matches = messages
        .filter((m) => m.content.toLowerCase().includes(needle))
        .slice(0, args.count ?? MAX_READ_LIMIT);
      return {
        channelId: args.channelId,
        scanned: messages.length,
        complete,
        count: matches.length,
        messages: matches.map(toMessageItem),
      };
    },
  }),
  defineTool({
    name: "get_message",
    title: "Get message",
    description:
      "Get one message of a channel or thread by ID, with its attachments, link, and the message it replies to. attachmentId keeps one attachment. includeReplies finds its replies by scanning the newer messages.",
    group: "messages",
    access: "read",
    input: z.object({
      channelId: channelIdParam,
      messageId: messageIdParam,
      attachmentId: snowflake("attachmentId").optional(),
      includeReplies: z.boolean().optional(),
      scanLimit: scanParam,
    }),
    run: async (args, ctx) => {
      if (args.scanLimit !== undefined && !args.includeReplies)
        throw new ToolError("scanLimit needs includeReplies");
      const channel = await requireMessageChannel(ctx, args.channelId);
      const message = await ctx.client.rest.channels.getMessage(
        args.channelId,
        args.messageId,
      );
      const referenced = repliedTo(message) && message.referencedMessage;
      const result = {
        ...toLinkedMessage(message, channel.guildID),
        ...(referenced ? { repliedMessage: toMessageItem(referenced) } : {}),
      };
      if (args.attachmentId) {
        const kept = (result.attachments ?? []).filter(
          (a) => a.id === args.attachmentId,
        );
        if (kept.length === 0)
          throw new ToolError(
            `Attachment ${args.attachmentId} is not on message ${args.messageId}`,
          );
        result.attachments = kept;
      }
      if (!args.includeReplies) return result;
      const { messages, complete } = await scanHistory(
        ctx,
        args.channelId,
        { after: message.id },
        args.scanLimit ?? DEFAULT_SCAN,
      );
      const replies = messages
        .filter((m) => repliedTo(m) === message.id)
        .sort(byIdAscending);
      return {
        ...result,
        replies: replies.map(toMessageItem),
        repliesScanned: messages.length,
        repliesComplete: complete,
      };
    },
  }),
  defineTool({
    name: "search_guild_messages",
    title: "Search server messages",
    description:
      "Search all messages of a server with Discord's search index, by text, author, mention, channel, or content type. Returns the total and up to 25 messages; page with offset.",
    group: "messages",
    access: "read",
    input: z.object({
      guildId,
      content: z.string().min(1).max(MAX_CONTENT_LENGTH).optional(),
      authorId: snowflake("authorId").optional(),
      mentions: snowflake("mentions").optional().describe("Mentioned user ID"),
      channelId: snowflake("channelId").optional(),
      has: z.array(z.enum(SEARCH_HAS)).optional(),
      minId: snowflake("minId").optional().describe("Sent after this ID"),
      maxId: snowflake("maxId").optional().describe("Sent before this ID"),
      offset: z.number().int().min(0).optional(),
      limit: z.number().int().min(1).max(25).optional().describe("Default 25"),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const result = await ctx.client.rest.guilds
        .searchMessages(
          id,
          definedOnly({
            content: args.content,
            authorIDs: args.authorId ? [args.authorId] : undefined,
            mentions: args.mentions ? [args.mentions] : undefined,
            channelIDs: args.channelId ? [args.channelId] : undefined,
            has: args.has,
            minID: args.minId,
            maxID: args.maxId,
            offset: args.offset,
          }),
        )
        .catch((error: unknown) => {
          throw searchFailure(error);
        });
      // Oceanic drops Discord's `hit` flag, so every message of the result groups is returned once.
      const unique = new Map<string, Message>();
      for (const group of result.messages) {
        for (const message of group) unique.set(message.id, message);
      }
      const messages = [...unique.values()]
        .slice(0, args.limit ?? 25)
        .map(toMessageItem);
      return {
        totalResults: result.totalResults,
        count: messages.length,
        messages,
      };
    },
  }),
  defineMergedTool({
    name: "react",
    title: "React to message",
    description:
      "Set the bot's emoji reaction on a message. emoji is Unicode, or name:id for a custom emoji.",
    group: "messages",
    key: "action",
    parts: [
      {
        name: "add_reaction",
        group: "messages",
        access: "write",
        value: "add",
        note: "add adds it.",
      },
      {
        name: "remove_reaction",
        group: "messages",
        access: "write",
        value: "remove",
        note: "remove removes it.",
      },
    ],
    input: z.object({
      channelId: channelIdParam,
      messageId: messageIdParam,
      emoji: z.string().min(1),
      action: z.enum(["add", "remove"]),
    }),
    run: async (args, ctx) => {
      await requireMessageChannel(ctx, args.channelId);
      const rest = ctx.client.rest.channels;
      await (args.action === "add"
        ? rest.createReaction(args.channelId, args.messageId, args.emoji)
        : rest.deleteReaction(args.channelId, args.messageId, args.emoji));
      return {
        action: args.action,
        emoji: args.emoji,
        messageId: args.messageId,
      };
    },
  }),
];
