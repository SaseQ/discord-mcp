import type { Member } from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineMergedTool, defineTool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { definedOnly, hasStatus, reasonOption } from "./util.ts";
import { findChannel, isAudioChannel, requireConnected } from "./voice.ts";

/** Discord allows a timeout of at most 28 days. */
const MAX_TIMEOUT_SECONDS = 2419200;
/** Discord allows deleting at most 7 days of messages on a ban. */
const MAX_DELETE_MESSAGE_SECONDS = 604800;

const MEMBER_ACTIONS = [
  "kick",
  "ban",
  "unban",
  "timeout",
  "move",
  "disconnect",
] as const;

/** Fetches a member, and turns a missing member into a ToolError. */
const fetchMember = async (
  ctx: Context,
  server: string,
  userId: string,
): Promise<Member> =>
  ctx.client.rest.guilds.getMember(server, userId).catch((error: unknown) => {
    if (hasStatus(error, 404))
      throw new ToolError("User not found in this server by userId");
    throw error;
  });

export const moderationTools = [
  defineMergedTool({
    name: "moderate_member",
    title: "Moderate member",
    description: "Act on a server member, or a banned user for unban.",
    group: "moderation",
    key: "action",
    parts: [
      {
        name: "kick_member",
        group: "moderation",
        access: "destructive",
        value: "kick",
        note: "kick removes them; they can rejoin with an invite.",
      },
      {
        name: "ban_member",
        group: "moderation",
        access: "destructive",
        value: "ban",
        params: ["deleteMessageSeconds"],
        note: "ban also deletes deleteMessageSeconds of their messages.",
      },
      {
        name: "unban_member",
        group: "moderation",
        access: "write",
        value: "unban",
        note: "unban lets them rejoin.",
      },
      {
        name: "timeout_member",
        group: "moderation",
        access: "write",
        value: "timeout",
        params: ["durationSeconds"],
        note: "timeout mutes them for durationSeconds, at most 28 days; 0 removes it.",
      },
      {
        name: "move_member",
        group: "voice",
        access: "write",
        value: "move",
        params: ["channelId"],
        note: "move takes a connected member to the voice or stage channelId.",
      },
      {
        name: "disconnect_member",
        group: "voice",
        access: "write",
        value: "disconnect",
        note: "disconnect removes them from voice.",
      },
    ],
    input: z.object({
      action: z.enum(MEMBER_ACTIONS),
      guildId,
      userId: snowflake("User ID"),
      deleteMessageSeconds: z
        .number()
        .int()
        .min(0)
        .max(MAX_DELETE_MESSAGE_SECONDS)
        .optional(),
      durationSeconds: z
        .number()
        .int()
        .min(0)
        .max(MAX_TIMEOUT_SECONDS)
        .optional(),
      channelId: snowflake("Channel ID").optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const guilds = ctx.client.rest.guilds;
      switch (args.action) {
        case "kick": {
          const member = await fetchMember(ctx, id, args.userId);
          await guilds.removeMember(id, args.userId, args.reason);
          return { userId: args.userId, username: member.user.username };
        }
        case "ban": {
          const deleteMessageSeconds = args.deleteMessageSeconds ?? 0;
          await guilds.createBan(id, args.userId, {
            deleteMessageSeconds,
            ...reasonOption(args.reason),
          });
          return { userId: args.userId, deleteMessageSeconds };
        }
        case "unban":
          await guilds.removeBan(id, args.userId, args.reason);
          return { userId: args.userId };
        case "timeout": {
          if (args.durationSeconds === undefined)
            throw new ToolError("timeout needs durationSeconds");
          const member = await fetchMember(ctx, id, args.userId);
          const until =
            args.durationSeconds === 0
              ? null
              : new Date(
                  Date.now() + args.durationSeconds * 1000,
                ).toISOString();
          await guilds.editMember(id, args.userId, {
            communicationDisabledUntil: until,
            ...reasonOption(args.reason),
          });
          return {
            userId: args.userId,
            username: member.user.username,
            durationSeconds: args.durationSeconds,
            timeoutUntil: until,
          };
        }
        case "move": {
          if (args.channelId === undefined)
            throw new ToolError("move needs channelId");
          const target = await findChannel(ctx, id, args.channelId);
          if (!isAudioChannel(target.type))
            throw new ToolError(
              "Target channel is not a voice or stage channel",
            );
          await requireConnected(ctx, id, args.userId);
          await guilds.editMember(
            id,
            args.userId,
            definedOnly({ channelID: args.channelId, reason: args.reason }),
          );
          return { channelName: target.name };
        }
        case "disconnect":
          await requireConnected(ctx, id, args.userId);
          await guilds.editMember(
            id,
            args.userId,
            definedOnly({ channelID: null, reason: args.reason }),
          );
          return { disconnected: true };
      }
    },
  }),

  defineTool({
    name: "set_nickname",
    title: "Set nickname",
    description: "Set or reset a member's nickname.",
    group: "moderation",
    access: "write",
    input: z.object({
      guildId,
      userId: snowflake("User ID"),
      nick: z.string().optional().describe("Empty or omitted resets it"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const member = await fetchMember(ctx, id, args.userId);
      const nick = args.nick?.trim() ? args.nick.trim() : null;
      await ctx.client.rest.guilds.editMember(id, args.userId, {
        nick,
        ...reasonOption(args.reason),
      });
      return {
        userId: args.userId,
        username: member.user.username,
        nick,
      };
    },
  }),

  defineTool({
    name: "get_bans",
    title: "Get bans",
    description: "List banned users with their ban reasons.",
    group: "moderation",
    access: "read",
    input: z.object({
      guildId,
      limit: z.number().int().min(1).max(1000).default(50),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const bans = await ctx.client.rest.guilds.getBans(id, {
        limit: args.limit,
      });
      return {
        bans: bans.map((ban) => ({
          userId: ban.user.id,
          username: ban.user.username,
          globalName: ban.user.globalName,
          reason: ban.reason,
        })),
      };
    },
  }),
];
