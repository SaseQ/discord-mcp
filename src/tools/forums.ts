import {
  ChannelFlags,
  ChannelTypes,
  type ForumChannel,
  ForumLayoutTypes,
  SortOrderTypes,
} from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineTool, ToolError } from "../tool.ts";
import { mentionPolicy } from "./format.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { definedOnly, hasStatus, reasonOption } from "./util.ts";

/** Discord applies at most this many tags to a forum post. */
const MAX_TAGS = 5;
/** Discord allows a slowmode of at most six hours. */
const MAX_SLOWMODE_SECONDS = 21600;

const SORT_ORDERS = {
  RECENT_ACTIVITY: SortOrderTypes.LATEST_ACTIVITY,
  CREATION_TIME: SortOrderTypes.CREATION_DATE,
} as const;

const LAYOUTS = {
  LIST_VIEW: ForumLayoutTypes.LIST,
  GALLERY_VIEW: ForumLayoutTypes.GRID,
} as const;

const forumId = snowflake("Forum channel ID");
const postId = snowflake("Forum post ID");
const tagIds = z
  .string()
  .max(500)
  .optional()
  .describe("Comma-separated IDs or names, max 5. Empty string clears");

type ForumTag = ForumChannel["availableTags"][number];

/** Maps a Discord "not found" response to a ToolError with the given message. */
const notFound =
  (message: string) =>
  (error: unknown): never => {
    if (hasStatus(error, 404)) throw new ToolError(message);
    throw error;
  };

/** Returns the forum channel of the server, or fails when the channel is not a forum in it. */
const guildForum = async (ctx: Context, guild: string, channelId: string) => {
  const channel = (await ctx.client.rest.guilds.getChannels(guild)).find(
    (c) => c.id === channelId,
  );
  if (channel?.type !== ChannelTypes.GUILD_FORUM)
    throw new ToolError("Channel not found or is not a forum channel");
  return channel;
};

/** Returns a forum post of the server, or fails when the ID is not a post in it. */
const guildPost = async (ctx: Context, guild: string, id: string) => {
  const thread = await ctx.client.rest.channels
    .get(id)
    .catch(notFound("Forum post not found by postId"));
  if (thread.type !== ChannelTypes.PUBLIC_THREAD || thread.guildID !== guild)
    throw new ToolError("Forum post not found by postId");
  return thread;
};

/**
 * Turns the tag IDs or tag names of a caller into tag IDs.
 * A name matches a tag only when exactly one tag of the forum has it, ignoring case.
 */
const resolveTags = (requested: string, tags: ForumTag[]): string[] => {
  const ids = new Set<string>();
  for (const item of requested.split(",")) {
    const value = item.trim();
    if (value === "") continue;
    const byId = tags.find((tag) => tag.id === value);
    if (byId) {
      ids.add(byId.id);
      continue;
    }
    const byName = tags.filter(
      (tag) => tag.name.toLowerCase() === value.toLowerCase(),
    );
    const [match] = byName;
    if (match === undefined)
      throw new ToolError(`Unknown tag of this forum: ${value}`);
    if (byName.length > 1)
      throw new ToolError(
        `Tag name is not unique in this forum, use its ID: ${value}`,
      );
    ids.add(match.id);
  }
  if (ids.size > MAX_TAGS)
    throw new ToolError(`A forum post can have at most ${MAX_TAGS} tags`);
  return [...ids];
};

const requireCategory = async (
  ctx: Context,
  guild: string,
  categoryId: string,
) => {
  const found = (await ctx.client.rest.guilds.getChannels(guild)).some(
    (c) => c.id === categoryId && c.type === ChannelTypes.GUILD_CATEGORY,
  );
  if (!found) throw new ToolError("Category not found by categoryId");
};

const describeForum = (forum: ForumChannel) => ({
  id: forum.id,
  name: forum.name,
  categoryId: forum.parentID,
  topic: forum.topic,
  nsfw: forum.nsfw,
  slowmode: forum.rateLimitPerUser,
  position: forum.position,
});

const describeTag = (tag: ForumTag) => ({
  id: tag.id,
  name: tag.name,
  moderated: tag.moderated,
  emoji: tag.emoji?.name ?? null,
});

export const forumTools = [
  defineTool({
    name: "edit_forum_channel",
    title: "Edit forum channel",
    description:
      "Edit a forum channel: name, topic, NSFW, slowmode, category, position, sort, layout.",
    group: "forums",
    access: "write",
    input: z.object({
      guildId,
      channelId: forumId,
      name: z.string().min(1).max(100).optional(),
      topic: z.string().max(4096).optional().describe("Post guidelines"),
      nsfw: z.boolean().optional().describe("Whether the channel is NSFW"),
      slowmode: z
        .number()
        .int()
        .min(0)
        .max(MAX_SLOWMODE_SECONDS)
        .optional()
        .describe("Default slowmode for new posts, in seconds (0-21600)"),
      categoryId: z
        .string()
        .max(20)
        .optional()
        .describe("Empty string removes the category"),
      position: z
        .number()
        .int()
        .min(0)
        .optional()
        .describe("Position in the channel list"),
      defaultSort: z.enum(["RECENT_ACTIVITY", "CREATION_TIME"]).optional(),
      defaultLayout: z.enum(["LIST_VIEW", "GALLERY_VIEW"]).optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const guild = ctx.guildId(args.guildId);
      const forum = await guildForum(ctx, guild, args.channelId);
      const fields = definedOnly({
        name: args.name,
        topic: args.topic,
        // Oceanic types `nsfw` on edits as a string, but Discord takes a boolean.
        nsfw: args.nsfw as unknown as string | undefined,
        rateLimitPerUser: args.slowmode,
        parentID:
          args.categoryId === undefined ? undefined : args.categoryId || null,
        position: args.position,
        defaultSortOrder:
          args.defaultSort === undefined
            ? undefined
            : SORT_ORDERS[args.defaultSort],
        defaultForumLayout:
          args.defaultLayout === undefined
            ? undefined
            : LAYOUTS[args.defaultLayout],
      });
      if (Object.keys(fields).length === 0)
        throw new ToolError(
          "At least one forum channel field must be provided",
        );
      if (args.categoryId) await requireCategory(ctx, guild, args.categoryId);
      const edited = await ctx.client.rest.channels.edit<ForumChannel>(
        forum.id,
        { ...fields, ...reasonOption(args.reason) },
      );
      return { id: edited.id, name: edited.name };
    },
  }),
  defineTool({
    name: "list_forum_channels",
    title: "List forum channels",
    description: "List forum channels.",
    group: "forums",
    access: "read",
    input: z.object({ guildId }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      return channels.flatMap((channel) =>
        channel.type === ChannelTypes.GUILD_FORUM
          ? [
              {
                id: channel.id,
                name: channel.name,
                categoryId: channel.parentID,
              },
            ]
          : [],
      );
    },
  }),
  defineTool({
    name: "get_forum_channel_info",
    title: "Get forum channel info",
    description: "Get a forum channel's settings and tags.",
    group: "forums",
    access: "read",
    input: z.object({ guildId, channelId: forumId }),
    run: async (args, ctx) => {
      const forum = await guildForum(
        ctx,
        ctx.guildId(args.guildId),
        args.channelId,
      );
      return {
        ...describeForum(forum),
        tags: forum.availableTags.map(describeTag),
      };
    },
  }),
  defineTool({
    name: "create_forum_post",
    title: "Create forum post",
    description:
      "Create a forum post (thread) with a first message and optional tags.",
    group: "forums",
    access: "write",
    input: z.object({
      guildId,
      channelId: forumId,
      title: z.string().min(1).max(100),
      message: z.string().min(1).max(2000),
      tagIds,
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const forum = await guildForum(
        ctx,
        ctx.guildId(args.guildId),
        args.channelId,
      );
      const appliedTags =
        args.tagIds === undefined
          ? undefined
          : resolveTags(args.tagIds, forum.availableTags);
      const post =
        await ctx.client.rest.channels.startThreadInThreadOnlyChannel(
          forum.id,
          {
            name: args.title,
            message: {
              content: args.message,
              allowedMentions: mentionPolicy(false),
            },
            ...definedOnly({ appliedTags }),
            ...reasonOption(args.reason),
          },
        );
      return { id: post.id, name: post.name, appliedTags: post.appliedTags };
    },
  }),
  defineTool({
    name: "list_forum_posts",
    title: "List forum posts",
    description: "List active posts (threads) of a forum channel.",
    group: "forums",
    access: "read",
    input: z.object({ guildId, channelId: forumId }),
    run: async (args, ctx) => {
      const guild = ctx.guildId(args.guildId);
      await guildForum(ctx, guild, args.channelId);
      const { threads } = await ctx.client.rest.guilds.getActiveThreads(guild);
      return threads.flatMap((thread) =>
        thread.type === ChannelTypes.PUBLIC_THREAD &&
        thread.parentID === args.channelId
          ? [
              {
                id: thread.id,
                name: thread.name,
                appliedTags: thread.appliedTags,
                locked: thread.threadMetadata.locked ?? false,
                archived: thread.threadMetadata.archived ?? false,
                pinned: (thread.flags & ChannelFlags.PINNED) !== 0,
              },
            ]
          : [],
      );
    },
  }),
  defineTool({
    name: "modify_forum_post",
    title: "Modify forum post",
    description: "Lock, archive, pin, or retag a forum post.",
    group: "forums",
    access: "write",
    input: z.object({
      guildId,
      postId,
      locked: z.boolean().optional(),
      archived: z.boolean().optional(),
      pinned: z.boolean().optional(),
      tagIds,
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      if (
        args.locked === undefined &&
        args.archived === undefined &&
        args.pinned === undefined &&
        args.tagIds === undefined
      )
        throw new ToolError(
          "At least one of locked, archived, pinned, or tagIds must be provided",
        );
      const guild = ctx.guildId(args.guildId);
      const post = await guildPost(ctx, guild, args.postId);
      const forum = await guildForum(ctx, guild, post.parentID);
      const appliedTags =
        args.tagIds === undefined
          ? undefined
          : resolveTags(args.tagIds, forum.availableTags);
      const flags =
        args.pinned === undefined
          ? undefined
          : args.pinned
            ? post.flags | ChannelFlags.PINNED
            : post.flags & ~ChannelFlags.PINNED;
      const edited = await ctx.client.rest.channels.edit(post.id, {
        ...definedOnly({
          locked: args.locked,
          archived: args.archived,
          flags,
          appliedTags,
        }),
        ...reasonOption(args.reason),
      });
      return { id: edited.id, name: edited.name };
    },
  }),
];
