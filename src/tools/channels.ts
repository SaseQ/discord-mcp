import { type AnyThreadChannel, ChannelTypes } from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineMergedTool, defineTool, type Tool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { channelTypeName, definedOnly, hasStatus } from "./util.ts";

/** A channel of a server, as `rest.guilds.getChannels` returns it. It leaves out threads. */
export type GuildChannel = Awaited<
  ReturnType<Context["client"]["rest"]["guilds"]["getChannels"]>
>[number];

const isThread = (channel: {
  type: ChannelTypes;
}): channel is AnyThreadChannel =>
  channel.type === ChannelTypes.ANNOUNCEMENT_THREAD ||
  channel.type === ChannelTypes.PUBLIC_THREAD ||
  channel.type === ChannelTypes.PRIVATE_THREAD;

/** The channel with the given ID, which must be in the server whose channels are given. */
export const findChannel = (
  channels: GuildChannel[],
  channelId: string,
): GuildChannel => {
  const channel = channels.find((c) => c.id === channelId);
  if (!channel)
    throw new ToolError(`Channel ${channelId} was not found in this server`);
  return channel;
};

/** The category with the given ID, which must be a category of the server whose channels are given. */
export const findCategory = (
  channels: GuildChannel[],
  categoryId: string,
): GuildChannel => {
  const category = channels.find(
    (c) => c.id === categoryId && c.type === ChannelTypes.GUILD_CATEGORY,
  );
  if (!category)
    throw new ToolError(`Category ${categoryId} was not found in this server`);
  return category;
};

/** The fields that the tools return. A field that the channel type lacks is left out of the JSON. */
export const describeChannel = (channel: GuildChannel) => ({
  id: channel.id,
  name: channel.name,
  type: channelTypeName(channel.type),
  parentId: channel.parentID,
  position: "position" in channel ? channel.position : undefined,
  topic: "topic" in channel ? channel.topic : undefined,
  nsfw: "nsfw" in channel ? channel.nsfw : undefined,
  rateLimitPerUser:
    "rateLimitPerUser" in channel ? channel.rateLimitPerUser : undefined,
});

/** Runs a call on one channel, and turns Discord's 404 into a ToolError, because the channel is gone. */
export const onChannel = async <T>(
  channelId: string,
  call: () => Promise<T>,
): Promise<T> => {
  try {
    return await call();
  } catch (error) {
    if (hasStatus(error, 404))
      throw new ToolError(`Channel ${channelId} not found`);
    throw error;
  }
};

/** The parent for an edit: `undefined` keeps it, an empty string removes it, and an ID must be a category of the server. */
const parentFor = (
  channels: GuildChannel[],
  categoryId: string | undefined,
) => {
  if (categoryId === undefined) return undefined;
  if (categoryId === "") return null;
  return findCategory(channels, categoryId).id;
};

const parentParam = z
  .union([snowflake("Category ID"), z.literal("")])
  .optional()
  .describe("Empty string removes the category");

const slowmode = z
  .number()
  .int()
  .min(0)
  .max(21600)
  .optional()
  .describe("Seconds");

const position = z.number().int().min(0).optional();

const channelName = z.string().min(1).max(100);

const CHANNEL_TYPES = {
  text: ChannelTypes.GUILD_TEXT,
  voice: ChannelTypes.GUILD_VOICE,
  stage: ChannelTypes.GUILD_STAGE_VOICE,
  forum: ChannelTypes.GUILD_FORUM,
} as const;

export const channelTools: Tool[] = [
  defineTool({
    name: "delete_channel",
    title: "Delete channel",
    description: "Delete a channel.",
    group: "channels",
    access: "destructive",
    input: z.object({
      guildId,
      channelId: snowflake("Channel ID"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const channel = findChannel(channels, args.channelId);
      await onChannel(channel.id, () =>
        ctx.client.rest.channels.delete(channel.id, args.reason),
      );
      return { deleted: true, ...describeChannel(channel) };
    },
  }),
  defineMergedTool({
    name: "create_channel",
    title: "Create channel",
    description: "Create a channel, optionally in a category.",
    group: "channels",
    key: "type",
    parts: [
      {
        name: "create_text_channel",
        group: "channels",
        access: "write",
        value: "text",
        note: "text takes topic (at most 1024 characters), nsfw, and slowmode.",
        params: ["topic", "nsfw", "slowmode"],
      },
      {
        name: "create_voice_channel",
        group: "voice",
        access: "write",
        value: "voice",
        note: "voice takes bitrate and userLimit.",
        params: ["bitrate", "userLimit"],
      },
      {
        name: "create_stage_channel",
        group: "voice",
        access: "write",
        value: "stage",
        note: "stage takes bitrate.",
        params: ["bitrate"],
      },
      {
        name: "create_forum_channel",
        group: "forums",
        access: "write",
        value: "forum",
        note: "forum takes topic as the post guidelines, nsfw, and slowmode for new posts.",
        params: ["topic", "nsfw", "slowmode"],
      },
    ],
    input: z.object({
      type: z.enum(["text", "voice", "stage", "forum"]),
      guildId,
      name: channelName,
      categoryId: snowflake("Category ID").optional(),
      topic: z.string().max(4096).optional(),
      nsfw: z.boolean().optional(),
      slowmode,
      position,
      bitrate: z
        .number()
        .int()
        .min(8000)
        .optional()
        .describe("Bits per second"),
      userLimit: z
        .number()
        .int()
        .min(0)
        .max(99)
        .optional()
        .describe("0 means unlimited"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      if (args.type === "text" && (args.topic?.length ?? 0) > 1024)
        throw new ToolError("A text channel topic is at most 1024 characters");
      const id = ctx.guildId(args.guildId);
      const parentID =
        args.categoryId === undefined
          ? undefined
          : findCategory(
              await ctx.client.rest.guilds.getChannels(id),
              args.categoryId,
            ).id;
      const channel = await ctx.client.rest.guilds.createChannel(
        id,
        CHANNEL_TYPES[args.type],
        {
          name: args.name,
          ...definedOnly({
            parentID,
            topic: args.topic,
            nsfw: args.nsfw,
            rateLimitPerUser: args.slowmode,
            position: args.position,
            bitrate: args.bitrate,
            userLimit: args.userLimit,
            reason: args.reason,
          }),
        },
      );
      return describeChannel(channel);
    },
  }),
  defineTool({
    name: "find_channel",
    title: "Find channel",
    description:
      "Find channels by name, case-insensitive. Returns every match.",
    group: "channels",
    access: "read",
    input: z.object({
      guildId,
      channelName,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const wanted = args.channelName.toLowerCase();
      const matches = channels.filter((c) => c.name.toLowerCase() === wanted);
      if (matches.length === 0)
        throw new ToolError(`No channel named ${args.channelName} was found`);
      return matches.map(describeChannel);
    },
  }),
  defineTool({
    name: "list_channels",
    title: "List channels",
    description: "List the server's channels. Excludes threads.",
    group: "channels",
    access: "read",
    input: z.object({ guildId }),
    run: async (args, ctx) =>
      (await ctx.client.rest.guilds.getChannels(ctx.guildId(args.guildId))).map(
        describeChannel,
      ),
  }),
  defineTool({
    name: "edit_text_channel",
    title: "Edit text channel",
    description:
      "Edit a text or announcement channel: name, topic, NSFW, slowmode, category, position. Only given fields change.",
    group: "channels",
    access: "write",
    input: z.object({
      guildId,
      channelId: snowflake("Channel ID"),
      name: channelName.optional(),
      topic: z.string().max(1024).optional(),
      nsfw: z.boolean().optional(),
      slowmode,
      categoryId: parentParam,
      position,
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const channel = findChannel(channels, args.channelId);
      if (
        channel.type !== ChannelTypes.GUILD_TEXT &&
        channel.type !== ChannelTypes.GUILD_ANNOUNCEMENT
      )
        throw new ToolError(
          `Channel ${channel.id} is not a text or announcement channel`,
        );
      if (
        args.slowmode !== undefined &&
        channel.type !== ChannelTypes.GUILD_TEXT
      )
        throw new ToolError("slowmode is supported only for text channels");
      const edited = await onChannel(channel.id, () =>
        ctx.client.rest.channels.edit<GuildChannel>(
          channel.id,
          definedOnly({
            name: args.name,
            topic: args.topic,
            // Oceanic types nsfw as a string, but Discord takes a boolean and Oceanic passes it through.
            nsfw: args.nsfw as unknown as string,
            rateLimitPerUser: args.slowmode,
            parentID: parentFor(channels, args.categoryId),
            position: args.position,
            reason: args.reason,
          }),
        ),
      );
      return describeChannel(edited);
    },
  }),
  defineTool({
    name: "get_channel_info",
    title: "Get channel info",
    description:
      "Get channel or thread details with creation time. For a thread: parent, owner, archived, locked, message and member counts.",
    group: "channels",
    access: "read",
    input: z.object({ guildId, channelId: snowflake("Channel ID") }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const channels = await ctx.client.rest.guilds.getChannels(id);
      const channel = channels.find((c) => c.id === args.channelId);
      if (channel) {
        return {
          ...describeChannel(channel),
          createdAt: channel.createdAt.toISOString(),
        };
      }
      // The guild channel list leaves out threads, so look the ID up directly.
      const thread = await onChannel(args.channelId, () =>
        ctx.client.rest.channels.get(args.channelId),
      );
      if (!isThread(thread) || thread.guildID !== id)
        throw new ToolError(
          `Channel ${args.channelId} was not found in this server`,
        );
      return {
        id: thread.id,
        name: thread.name,
        type: channelTypeName(thread.type),
        parentId: thread.parentID,
        ownerId: thread.ownerID,
        archived: thread.threadMetadata.archived,
        locked: thread.threadMetadata.locked,
        messageCount: thread.messageCount,
        memberCount: thread.memberCount,
        createdAt: thread.createdAt.toISOString(),
      };
    },
  }),
  defineTool({
    name: "move_channel",
    title: "Move channel",
    description:
      "Move a channel to another category or out of its category, or change its position. Not for categories.",
    group: "channels",
    access: "write",
    input: z.object({
      guildId,
      channelId: snowflake("Channel ID"),
      categoryId: parentParam,
      position,
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const channel = findChannel(channels, args.channelId);
      if (channel.type === ChannelTypes.GUILD_CATEGORY)
        throw new ToolError(
          `Channel ${channel.id} is a category and cannot be moved`,
        );
      const edited = await onChannel(channel.id, () =>
        ctx.client.rest.channels.edit<GuildChannel>(
          channel.id,
          definedOnly({
            parentID: parentFor(channels, args.categoryId),
            position: args.position,
            reason: args.reason,
          }),
        ),
      );
      return describeChannel(edited);
    },
  }),
];
