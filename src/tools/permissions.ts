import { OverwriteTypes, Permissions } from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineMergedTool, defineTool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { reasonOption } from "./util.ts";

/** The permission flags by their Discord names, as Oceanic defines them. */
const FLAGS = new Map<string, bigint>(Object.entries(Permissions));

interface Bits {
  allow: bigint;
  deny: bigint;
}

interface Target {
  type: "role" | "member";
  id: string;
}

/** Turns permission names into one bitfield, and rejects every name that Discord does not define. */
const namesToBits = (names: string[]): bigint => {
  const unknown = names.filter((name) => !FLAGS.has(name));
  if (unknown.length > 0)
    throw new ToolError(`Unknown permission names: ${unknown.join(", ")}`);
  return names.reduce((bits, name) => bits | (FLAGS.get(name) ?? 0n), 0n);
};

/** The names of the flags that a bitfield sets. */
const bitsToNames = (bits: bigint): string[] =>
  [...FLAGS].filter(([, flag]) => (bits & flag) === flag).map(([name]) => name);

const describeBits = (bits: bigint) => ({
  bits: bits.toString(),
  names: bitsToNames(bits),
});

/** Reads one side of an overwrite from a raw bitfield or a CSV of names, and neither means 0. */
const sideBits = (
  raw: string | undefined,
  csv: string | undefined,
  side: string,
): bigint => {
  const names = (csv ?? "")
    .split(",")
    .map((name) => name.trim())
    .filter((name) => name !== "");
  if (raw !== undefined && names.length > 0)
    throw new ToolError(
      `Give either the ${side} bitfield or its permission names, not both`,
    );
  if (raw !== undefined) return BigInt(raw);
  return namesToBits(names);
};

/** Reads a CSV of permission names, and rejects every name that Discord does not define. */
const csvBits = (csv: string | undefined): bigint =>
  namesToBits(
    (csv ?? "")
      .split(",")
      .map((name) => name.trim())
      .filter((name) => name !== ""),
  );

/** Reads the sides of an overwrite, and rejects a missing change, a flag on both sides, and a clear without merge. */
const overwriteBits = (args: {
  allowRaw?: string | undefined;
  allowPermissions?: string | undefined;
  denyRaw?: string | undefined;
  denyPermissions?: string | undefined;
  merge?: boolean | undefined;
  clearPermissions?: string | undefined;
}): Bits & { clear: bigint } => {
  if (!args.merge && args.clearPermissions !== undefined)
    throw new ToolError("clearPermissions is valid only with merge: true");
  const allow = sideBits(args.allowRaw, args.allowPermissions, "allow");
  const deny = sideBits(args.denyRaw, args.denyPermissions, "deny");
  const clear = csvBits(args.clearPermissions);
  if (allow === 0n && deny === 0n && clear === 0n)
    throw new ToolError(
      args.merge
        ? "Give at least one allow, deny, or clear permission"
        : "Give at least one allow or deny permission",
    );
  if ((allow & deny) !== 0n)
    throw new ToolError("allow and deny cannot contain the same permission");
  if (((allow | deny) & clear) !== 0n)
    throw new ToolError(
      "clearPermissions cannot contain a permission that allow or deny sets",
    );
  return { allow, deny, clear };
};

/** Fetches a channel of the server, and turns a channel of another server into a ToolError. */
const findChannel = async (ctx: Context, server: string, channelId: string) => {
  const channel = (await ctx.client.rest.guilds.getChannels(server)).find(
    (c) => c.id === channelId,
  );
  if (!channel)
    throw new ToolError("Channel not found in this server by channelId");
  return channel;
};

/** Fetches a role of the server, and turns a missing role into a ToolError. */
const findRole = async (ctx: Context, server: string, roleId: string) => {
  const role = (await ctx.client.rest.guilds.getRoles(server)).find(
    (r) => r.id === roleId,
  );
  if (!role) throw new ToolError("Role not found in this server by roleId");
  return role;
};

/** Sets the overwrite of a target to the given sides. */
const setOverwrite = async (
  ctx: Context,
  channelId: string,
  target: Target,
  bits: Bits,
  reason: string | undefined,
) => {
  await ctx.client.rest.channels.editPermission(channelId, target.id, {
    type: target.type === "role" ? OverwriteTypes.ROLE : OverwriteTypes.MEMBER,
    allow: bits.allow,
    deny: bits.deny,
    ...reasonOption(reason),
  });
  return {
    channelId,
    target,
    allow: describeBits(bits.allow),
    deny: describeBits(bits.deny),
  };
};

const bitfield = (side: "allow" | "deny") =>
  z
    .string()
    .refine((v) => /^\d+$/.test(v), `${side}Raw must be a decimal bitfield`)
    .optional()
    .describe("Decimal bitfield");

const permissionNames = z
  .string()
  .optional()
  .describe("CSV of names, e.g. VIEW_CHANNEL");

const overwriteFields = {
  allowRaw: bitfield("allow"),
  denyRaw: bitfield("deny"),
  allowPermissions: permissionNames,
  denyPermissions: permissionNames,
};

export const permissionTools = [
  defineTool({
    name: "list_channel_permission_overwrites",
    title: "List channel permission overwrites",
    description:
      "List channel permission overwrites: target type and ID, allow and deny flags decoded into names.",
    group: "permissions",
    access: "read",
    input: z.object({
      guildId,
      channelId: snowflake("Channel ID"),
    }),
    run: async (args, ctx) => {
      const channel = await findChannel(
        ctx,
        ctx.guildId(args.guildId),
        args.channelId,
      );
      return {
        channelId: channel.id,
        overwrites: [...channel.permissionOverwrites.values()].map((o) => ({
          id: o.id,
          type: o.type === OverwriteTypes.ROLE ? "role" : "member",
          allow: describeBits(o.allow),
          deny: describeBits(o.deny),
        })),
      };
    },
  }),

  defineMergedTool({
    name: "upsert_channel_permissions",
    title: "Set channel permissions",
    description:
      "Create, replace, or merge a permission overwrite on a channel.",
    group: "permissions",
    key: "targetType",
    parts: [
      {
        name: "upsert_role_channel_permissions",
        group: "permissions",
        access: "write",
        value: "role",
        note: "role: targetId is a role. Lock a channel read-only: role, targetId = the server ID (@everyone), denyPermissions SEND_MESSAGES, merge true; unlock with clearPermissions SEND_MESSAGES.",
      },
      {
        name: "upsert_member_channel_permissions",
        group: "permissions",
        access: "write",
        value: "member",
        note: "member: targetId is a user.",
      },
    ],
    input: z.object({
      guildId,
      channelId: snowflake("Channel ID"),
      targetType: z.enum(["role", "member"]),
      targetId: snowflake("Role or user ID"),
      ...overwriteFields,
      merge: z
        .boolean()
        .optional()
        .describe("Keep other bits of the old overwrite, default false"),
      clearPermissions: z
        .string()
        .optional()
        .describe("CSV of names to reset, merge only"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const { clear, ...given } = overwriteBits(args);
      const server = ctx.guildId(args.guildId);
      const channel = await findChannel(ctx, server, args.channelId);
      if (args.targetType === "role")
        await findRole(ctx, server, args.targetId);
      let bits: Bits = given;
      if (args.merge) {
        const old = channel.permissionOverwrites.get(args.targetId);
        const oldAllow = old?.allow ?? 0n;
        const oldDeny = old?.deny ?? 0n;
        bits = {
          allow: (oldAllow | given.allow) & ~given.deny & ~clear,
          deny: (oldDeny | given.deny) & ~given.allow & ~clear,
        };
      }
      return setOverwrite(
        ctx,
        args.channelId,
        { type: args.targetType, id: args.targetId },
        bits,
        args.reason,
      );
    },
  }),

  defineTool({
    name: "delete_channel_permission_overwrite",
    title: "Delete channel permission overwrite",
    description:
      "Delete a role or member permission overwrite from a channel; the target falls back to channel defaults.",
    group: "permissions",
    access: "destructive",
    input: z.object({
      guildId,
      channelId: snowflake("Channel ID"),
      targetType: z.enum(["role", "member"]),
      targetId: snowflake("Role or user ID"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channel = await findChannel(
        ctx,
        ctx.guildId(args.guildId),
        args.channelId,
      );
      const overwrite = [...channel.permissionOverwrites.values()].find(
        (o) => o.id === args.targetId,
      );
      if (!overwrite)
        throw new ToolError("No permission overwrite found for targetId");
      const actual = overwrite.type === OverwriteTypes.ROLE ? "role" : "member";
      if (actual !== args.targetType)
        throw new ToolError(
          `The overwrite for targetId is a ${actual} overwrite, not a ${args.targetType} one`,
        );
      await ctx.client.rest.channels.deletePermission(
        args.channelId,
        args.targetId,
        args.reason,
      );
      return {
        channelId: args.channelId,
        targetType: args.targetType,
        targetId: args.targetId,
        deleted: true,
      };
    },
  }),
];
