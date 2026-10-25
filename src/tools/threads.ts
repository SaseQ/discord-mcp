import { ChannelTypes } from "oceanic.js";
import { z } from "zod";
import { defineTool, type Tool, ToolError } from "../tool.ts";
import { findChannel } from "./channels.ts";
import { guildId, snowflake } from "./schemas.ts";
import { channelTypeName, hasStatus } from "./util.ts";

/** Channels that can start a thread from one of their messages. */
const THREAD_PARENT_TYPES: readonly number[] = [
  ChannelTypes.GUILD_TEXT,
  ChannelTypes.GUILD_ANNOUNCEMENT,
];

/** The shape of a thread in every thread result. */
interface ThreadLike {
  id: string;
  name: string;
  type: ChannelTypes;
  parentID: string;
  messageCount: number;
  createdAt: Date;
  threadMetadata: { archived: boolean; locked: boolean };
}

const threadItem = (thread: ThreadLike, parentName: string | null) => ({
  id: thread.id,
  name: thread.name,
  type: channelTypeName(thread.type),
  parentId: thread.parentID,
  parentName,
  archived: thread.threadMetadata.archived,
  locked: thread.threadMetadata.locked,
  createdAt: thread.createdAt.toISOString(),
  messageCount: thread.messageCount,
});

const ARCHIVED_LIMIT = 100;

export const threadTools: Tool[] = [
  defineTool({
    name: "list_threads",
    title: "List threads",
    description:
      "List threads with parent channel names. Without channelId: the server's active threads. With channelId: that channel's active and up to 100 recent archived public threads.",
    group: "threads",
    access: "read",
    input: z.object({ guildId, channelId: snowflake("Channel ID").optional() }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const [{ threads: active }, channels] = await Promise.all([
        ctx.client.rest.guilds.getActiveThreads(id),
        ctx.client.rest.guilds.getChannels(id),
      ]);
      const parentNames = new Map(channels.map((c) => [c.id, c.name]));
      const toItem = (thread: ThreadLike) =>
        threadItem(thread, parentNames.get(thread.parentID) ?? null);
      if (args.channelId === undefined) {
        return { count: active.length, threads: active.map(toItem) };
      }
      const channelId = findChannel(channels, args.channelId).id;
      const threads = new Map<string, ThreadLike>(
        active.filter((t) => t.parentID === channelId).map((t) => [t.id, t]),
      );
      let note: string | undefined;
      try {
        const archived =
          await ctx.client.rest.channels.getPublicArchivedThreads(channelId, {
            limit: ARCHIVED_LIMIT,
          });
        for (const thread of archived.threads) {
          if (!threads.has(thread.id)) threads.set(thread.id, thread);
        }
        if (archived.hasMore)
          note = `Only the ${ARCHIVED_LIMIT} newest archived threads are listed.`;
      } catch (error) {
        if (!hasStatus(error, 403)) throw error;
        note =
          "Archived threads were skipped: Discord refused with 403 Forbidden. The bot needs View Channel and Read Message History in this channel.";
      }
      return {
        count: threads.size,
        threads: [...threads.values()].map(toItem),
        note,
      };
    },
  }),
  defineTool({
    name: "create_thread_from_message",
    title: "Create thread from message",
    description:
      "Start a thread from a message in a text or announcement channel.",
    group: "threads",
    access: "write",
    input: z.object({
      channelId: snowflake("Channel ID"),
      messageId: snowflake("Message ID"),
      name: z.string().min(1).max(100),
      autoArchiveDuration: z
        .literal([60, 1440, 4320, 10080])
        .optional()
        .describe("Idle minutes before archiving. Default: channel setting"),
    }),
    run: async (args, ctx) => {
      const channel = await ctx.client.rest.channels.get(args.channelId);
      if (!THREAD_PARENT_TYPES.includes(channel.type)) {
        throw new ToolError(
          `Channel ${args.channelId} is not a text or announcement channel`,
        );
      }
      const thread = await ctx.client.rest.channels.startThreadFromMessage(
        args.channelId,
        args.messageId,
        {
          name: args.name,
          ...(args.autoArchiveDuration === undefined
            ? {}
            : { autoArchiveDuration: args.autoArchiveDuration }),
        },
      );
      return threadItem(thread, "name" in channel ? channel.name : null);
    },
  }),
];
