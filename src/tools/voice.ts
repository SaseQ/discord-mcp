import { ChannelTypes } from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineTool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { channelTypeName, definedOnly, hasStatus } from "./util.ts";

const NOT_CONNECTED = "User is not connected to any voice channel";

export const isAudioChannel = (type: ChannelTypes) =>
  type === ChannelTypes.GUILD_VOICE || type === ChannelTypes.GUILD_STAGE_VOICE;

/** Fetches a channel of the server, and turns a missing channel into a ToolError. */
export const findChannel = async (
  ctx: Context,
  server: string,
  channelId: string,
) => {
  const channel = (await ctx.client.rest.guilds.getChannels(server)).find(
    (c) => c.id === channelId,
  );
  if (!channel)
    throw new ToolError("Channel not found in this server by channelId");
  return channel;
};

/** Throws a ToolError unless the member is connected to a voice channel of the server. */
export const requireConnected = async (
  ctx: Context,
  server: string,
  userId: string,
): Promise<void> => {
  const state = await ctx.client.rest.guilds
    .getVoiceState(server, userId)
    .catch((error: unknown) => {
      if (hasStatus(error, 404)) throw new ToolError(NOT_CONNECTED);
      throw error;
    });
  if (!state.channelID) throw new ToolError(NOT_CONNECTED);
};

const summarize = (channel: {
  id: string;
  name: string | null;
  type: ChannelTypes;
}) => ({
  id: channel.id,
  name: channel.name,
  type: channelTypeName(channel.type),
});

export const voiceTools = [
  defineTool({
    name: "edit_voice_channel",
    title: "Edit voice channel",
    description:
      "Edit a voice or stage channel: name, bitrate, user limit, voice region.",
    group: "voice",
    access: "write",
    input: z.object({
      channelId: snowflake("Channel ID"),
      name: z.string().min(1).max(100).optional(),
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
      rtcRegion: z
        .string()
        .optional()
        .describe("Such as rotterdam. Empty string sets automatic"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const current = await ctx.client.rest.channels
        .get(args.channelId)
        .catch((error: unknown) => {
          if (hasStatus(error, 404))
            throw new ToolError("Channel not found by channelId");
          throw error;
        });
      if (!isAudioChannel(current.type))
        throw new ToolError("Channel is not a voice or stage channel");
      const edited = await ctx.client.rest.channels.edit(
        args.channelId,
        definedOnly({
          name: args.name,
          bitrate: args.bitrate,
          userLimit: args.userLimit,
          rtcRegion:
            args.rtcRegion === undefined ? undefined : args.rtcRegion || null,
          reason: args.reason,
        }),
      );
      return summarize(edited);
    },
  }),

  defineTool({
    name: "modify_voice_state",
    title: "Server mute or deafen member",
    description: "Server mute or deafen a connected member.",
    group: "voice",
    access: "write",
    input: z.object({
      guildId,
      userId: snowflake("User ID"),
      mute: z.boolean().optional(),
      deafen: z.boolean().optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      if (args.mute === undefined && args.deafen === undefined)
        throw new ToolError("Give mute or deafen, or both");
      const server = ctx.guildId(args.guildId);
      await requireConnected(ctx, server, args.userId);
      await ctx.client.rest.guilds.editMember(
        server,
        args.userId,
        definedOnly({
          mute: args.mute,
          deaf: args.deafen,
          reason: args.reason,
        }),
      );
      return { mute: args.mute, deafen: args.deafen };
    },
  }),
];
