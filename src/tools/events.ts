import {
  GuildScheduledEventEntityTypes,
  GuildScheduledEventPrivacyLevels,
  type GuildScheduledEventStatuses,
} from "oceanic.js";
import { z } from "zod";
import { defineTool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { definedOnly, reasonOption } from "./util.ts";

/** An ISO 8601 date and time that ends with `Z` or an offset such as `+02:00`. */
const ISO_WITH_OFFSET =
  /^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}(:\d{2}(\.\d{1,9})?)?(Z|[+-]\d{2}:\d{2})$/i;

/** Parses a time that must include an offset, so that it means one instant everywhere. */
const parseEventTime = (value: string, field: string): Date => {
  if (!ISO_WITH_OFFSET.test(value))
    throw new ToolError(
      `${field} must be an ISO 8601 time with an offset, such as 2030-11-01T18:00:00Z`,
    );
  const time = new Date(value);
  if (Number.isNaN(time.getTime()))
    throw new ToolError(`${field} is not a valid time`);
  return time;
};

const assertInFuture = (time: Date, field: string) => {
  if (time.getTime() <= Date.now())
    throw new ToolError(`${field} must be in the future`);
};

const eventTimeInput = z
  .string()
  .min(1)
  .max(64)
  .describe("ISO 8601 with offset, e.g. 2030-11-01T18:00:00Z");

const eventId = snowflake("Scheduled event ID");

const statusInput = z
  .union([z.literal(1), z.literal(2), z.literal(3), z.literal(4)])
  .describe("1=Scheduled, 2=Active, 3=Completed, 4=Canceled");

export const eventTools = [
  defineTool({
    name: "create_guild_scheduled_event",
    title: "Create scheduled event",
    description:
      "Create a scheduled event. Types 1 and 2 need channelId; type 3 needs location and end time.",
    group: "events",
    access: "write",
    input: z.object({
      guildId,
      name: z.string().min(1).max(100),
      description: z.string().min(1).max(1000).optional(),
      scheduledStartTime: eventTimeInput,
      scheduledEndTime: eventTimeInput.optional(),
      entityType: z
        .union([z.literal(1), z.literal(2), z.literal(3)])
        .describe("1=Stage, 2=Voice, 3=External"),
      channelId: snowflake("Channel ID").optional(),
      location: z.string().min(1).max(100).optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const start = parseEventTime(
        args.scheduledStartTime,
        "scheduledStartTime",
      );
      assertInFuture(start, "scheduledStartTime");
      const end =
        args.scheduledEndTime === undefined
          ? undefined
          : parseEventTime(args.scheduledEndTime, "scheduledEndTime");
      if (end !== undefined && end.getTime() <= start.getTime())
        throw new ToolError(
          "scheduledEndTime must be after scheduledStartTime",
        );

      const common = {
        name: args.name,
        ...(args.description === undefined
          ? {}
          : { description: args.description }),
        privacyLevel: GuildScheduledEventPrivacyLevels.GUILD_ONLY,
        scheduledStartTime: start.toISOString(),
        ...reasonOption(args.reason),
      };

      if (args.entityType === 3) {
        if (args.location === undefined)
          throw new ToolError("location is required for external events");
        if (end === undefined)
          throw new ToolError(
            "scheduledEndTime is required for external events",
          );
        const event = await ctx.client.rest.guilds.createScheduledEvent(id, {
          ...common,
          entityType: GuildScheduledEventEntityTypes.EXTERNAL,
          entityMetadata: { location: args.location },
          scheduledEndTime: end.toISOString(),
        });
        return summarizeEvent(event);
      }

      if (args.channelId === undefined)
        throw new ToolError("channelId is required for stage and voice events");
      const channel = (await ctx.client.rest.guilds.getChannels(id)).find(
        (c) => c.id === args.channelId,
      );
      if (!channel)
        throw new ToolError(
          `Channel ${args.channelId} was not found in this server`,
        );
      const event = await ctx.client.rest.guilds.createScheduledEvent(id, {
        ...common,
        entityType:
          args.entityType === 1
            ? GuildScheduledEventEntityTypes.STAGE_INSTANCE
            : GuildScheduledEventEntityTypes.VOICE,
        channelID: channel.id,
        ...(end === undefined ? {} : { scheduledEndTime: end.toISOString() }),
      });
      return summarizeEvent(event);
    },
  }),

  defineTool({
    name: "edit_guild_scheduled_event",
    title: "Edit scheduled event",
    description:
      "Edit a scheduled event, or start, complete, or cancel it with status.",
    group: "events",
    access: "write",
    input: z.object({
      guildId,
      eventId,
      status: statusInput.optional(),
      name: z.string().min(1).max(100).optional(),
      description: z.string().min(1).max(1000).optional(),
      scheduledStartTime: eventTimeInput.optional(),
      location: z.string().min(1).max(100).optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const start =
        args.scheduledStartTime === undefined
          ? undefined
          : parseEventTime(args.scheduledStartTime, "scheduledStartTime");
      if (start !== undefined) assertInFuture(start, "scheduledStartTime");
      const event = await ctx.client.rest.guilds.editScheduledEvent(
        id,
        args.eventId,
        definedOnly({
          name: args.name,
          description: args.description,
          scheduledStartTime: start?.toISOString(),
          entityMetadata:
            args.location === undefined
              ? undefined
              : { location: args.location },
          status: args.status,
          ...reasonOption(args.reason),
        }),
      );
      return summarizeEvent(event);
    },
  }),

  defineTool({
    name: "delete_guild_scheduled_event",
    title: "Delete scheduled event",
    description: "Delete a scheduled event.",
    group: "events",
    access: "destructive",
    input: z.object({
      guildId,
      eventId,
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      await ctx.client.rest.guilds.deleteScheduledEvent(
        id,
        args.eventId,
        args.reason,
      );
      return { deleted: true, id: args.eventId };
    },
  }),

  defineTool({
    name: "list_guild_scheduled_events",
    title: "List scheduled events",
    description:
      "List active and scheduled events with times, status, and interested count.",
    group: "events",
    access: "read",
    input: z.object({
      guildId,
      withUserCount: z.boolean().optional(),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const withUserCount = args.withUserCount ?? true;
      const events = await ctx.client.rest.guilds.getScheduledEvents(
        id,
        withUserCount ? 1 : undefined,
      );
      return { events: events.map(summarizeEvent) };
    },
  }),

  defineTool({
    name: "get_guild_scheduled_event_users",
    title: "List event interested users",
    description:
      "List users interested in a scheduled event, with nickname and roles.",
    group: "events",
    access: "read",
    input: z.object({
      guildId,
      eventId,
      limit: z.number().int().min(1).max(100).optional(),
      withMember: z.boolean().optional(),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const users = await ctx.client.rest.guilds.getScheduledEventUsers(
        id,
        args.eventId,
        {
          limit: args.limit ?? 100,
          withMember: args.withMember ?? true,
        },
      );
      return {
        users: users.map((entry) => ({
          id: entry.user.id,
          username: entry.user.username,
          nickname: entry.member?.nick ?? null,
          roleIds: entry.member?.roles ?? [],
        })),
      };
    },
  }),
];

const summarizeEvent = (event: {
  id: string;
  name: string;
  description?: string | null | undefined;
  entityType: GuildScheduledEventEntityTypes;
  entityID: string | null;
  channelID: string | null;
  entityMetadata: { location?: string } | null;
  privacyLevel: GuildScheduledEventPrivacyLevels;
  scheduledStartTime: Date;
  scheduledEndTime: Date | null;
  status: GuildScheduledEventStatuses;
  userCount: number;
}) => ({
  id: event.id,
  name: event.name,
  description: event.description ?? null,
  entityType: event.entityType,
  status: event.status,
  privacyLevel: event.privacyLevel,
  channelId: event.channelID,
  location: event.entityMetadata?.location ?? null,
  startTime: event.scheduledStartTime.toISOString(),
  endTime: event.scheduledEndTime?.toISOString() ?? null,
  userCount: event.userCount,
});
