import type { Role } from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import { defineMergedTool, defineTool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { hasStatus, reasonOption } from "./util.ts";

/** The @everyone role has the same ID as its server, and Discord does not let a bot edit or assign it. */
const isEveryoneRole = (server: string, roleId: string) => server === roleId;

const summarize = (role: Role) => ({
  id: role.id,
  name: role.name,
  color: role.colors.primaryColor,
  position: role.position,
  hoist: role.hoist,
  mentionable: role.mentionable,
  permissions: role.permissions.allow.toString(),
});

/** Fetches a role of the server, and turns a missing role into a ToolError. */
const findRole = async (
  ctx: Context,
  server: string,
  roleId: string,
): Promise<Role> => {
  const role = (await ctx.client.rest.guilds.getRoles(server)).find(
    (r) => r.id === roleId,
  );
  if (!role) throw new ToolError("Role not found by roleId");
  return role;
};

/** Fetches a member, and turns a missing member into a ToolError. */
const fetchMember = async (ctx: Context, server: string, userId: string) =>
  ctx.client.rest.guilds.getMember(server, userId).catch((error: unknown) => {
    if (hasStatus(error, 404))
      throw new ToolError("User not found in this server by userId");
    throw error;
  });

/** The reason option of a positional Oceanic call, which omits it when unset. */

const permissionsSchema = z
  .string()
  .refine(
    (v) => /^\d+$/.test(v),
    "permissions must be a permission bitfield as a string",
  )
  .describe("Bitfield as a digit string");

export const roleTools = [
  defineTool({
    name: "list_roles",
    title: "List roles",
    description: "List roles with color, position, flags, and permissions.",
    group: "roles",
    access: "read",
    input: z.object({ guildId }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const roles = await ctx.client.rest.guilds.getRoles(id);
      return { roles: roles.map(summarize) };
    },
  }),

  defineTool({
    name: "create_role",
    title: "Create role",
    description: "Create a role.",
    group: "roles",
    access: "write",
    input: z.object({
      guildId,
      name: z.string().min(1).max(100),
      color: z
        .number()
        .int()
        .min(0)
        .max(0xffffff)
        .optional()
        .describe("RGB integer, 0 for none"),
      hoist: z.boolean().optional(),
      mentionable: z.boolean().optional(),
      permissions: permissionsSchema.optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const role = await ctx.client.rest.guilds.createRole(id, {
        name: args.name,
        color: args.color ?? 0,
        hoist: args.hoist ?? false,
        mentionable: args.mentionable ?? false,
        permissions: args.permissions ?? "0",
        ...reasonOption(args.reason),
      });
      return summarize(role);
    },
  }),

  defineTool({
    name: "edit_role",
    title: "Edit role",
    description: "Edit a role. Only given fields change. Not @everyone.",
    group: "roles",
    access: "write",
    input: z.object({
      guildId,
      roleId: snowflake("Role ID"),
      name: z.string().min(1).max(100).optional(),
      color: z
        .number()
        .int()
        .min(0)
        .max(0xffffff)
        .optional()
        .describe("RGB integer, 0 for none"),
      hoist: z.boolean().optional(),
      mentionable: z.boolean().optional(),
      permissions: permissionsSchema.optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      if (isEveryoneRole(id, args.roleId))
        throw new ToolError("The @everyone role cannot be edited");
      const role = await ctx.client.rest.guilds.editRole(id, args.roleId, {
        ...(args.name !== undefined ? { name: args.name } : {}),
        ...(args.color !== undefined ? { color: args.color } : {}),
        ...(args.hoist !== undefined ? { hoist: args.hoist } : {}),
        ...(args.mentionable !== undefined
          ? { mentionable: args.mentionable }
          : {}),
        ...(args.permissions !== undefined
          ? { permissions: args.permissions }
          : {}),
        ...reasonOption(args.reason),
      });
      return summarize(role);
    },
  }),

  defineTool({
    name: "delete_role",
    title: "Delete role",
    description: "Delete a role permanently. Not @everyone.",
    group: "roles",
    access: "destructive",
    input: z.object({
      guildId,
      roleId: snowflake("Role ID"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      if (isEveryoneRole(id, args.roleId))
        throw new ToolError("The @everyone role cannot be deleted");
      const role = await findRole(ctx, id, args.roleId);
      await ctx.client.rest.guilds.deleteRole(id, args.roleId, args.reason);
      return { id: role.id, name: role.name };
    },
  }),

  defineMergedTool({
    name: "set_member_role",
    title: "Set member role",
    description: "Set a role on a member. Not @everyone.",
    group: "roles",
    key: "action",
    parts: [
      {
        name: "assign_role",
        group: "roles",
        access: "write",
        value: "add",
        note: "add gives it.",
      },
      {
        name: "remove_role",
        group: "roles",
        access: "write",
        value: "remove",
        note: "remove takes it away.",
      },
    ],
    input: z.object({
      guildId,
      userId: snowflake("User ID"),
      roleId: snowflake("Role ID"),
      action: z.enum(["add", "remove"]),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      if (isEveryoneRole(id, args.roleId))
        throw new ToolError(
          args.action === "add"
            ? "The @everyone role cannot be assigned, because every member already has it"
            : "The @everyone role cannot be removed",
        );
      const member = await fetchMember(ctx, id, args.userId);
      const role = await findRole(ctx, id, args.roleId);
      if (args.action === "add")
        await ctx.client.rest.guilds.addMemberRole(
          id,
          args.userId,
          args.roleId,
          args.reason,
        );
      else
        await ctx.client.rest.guilds.removeMemberRole(
          id,
          args.userId,
          args.roleId,
          args.reason,
        );
      return {
        userId: args.userId,
        username: member.user.username,
        roleId: role.id,
        roleName: role.name,
      };
    },
  }),
];
