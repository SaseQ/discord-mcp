import { z } from "zod";
import type { Context } from "../context.ts";
import { defineTool, ToolError } from "../tool.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { definedOnly, reasonOption } from "./util.ts";

/** Discord's limit for the image of an emoji. */
const MAX_EMOJI_BYTES = 256 * 1024;

const startsWith = (bytes: Uint8Array, signature: number[], offset = 0) =>
  signature.every((byte, i) => bytes[offset + i] === byte);

/** Reads the image type from its magic bytes, which are the only trusted source of the MIME type. */
const imageMimeType = (bytes: Uint8Array): string => {
  if (startsWith(bytes, [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a]))
    return "image/png";
  if (startsWith(bytes, [0xff, 0xd8, 0xff])) return "image/jpeg";
  if (
    startsWith(bytes, [0x47, 0x49, 0x46, 0x38, 0x37, 0x61]) ||
    startsWith(bytes, [0x47, 0x49, 0x46, 0x38, 0x39, 0x61])
  )
    return "image/gif";
  if (
    startsWith(bytes, [0x52, 0x49, 0x46, 0x46]) &&
    startsWith(bytes, [0x57, 0x45, 0x42, 0x50], 8)
  )
    return "image/webp";
  throw new ToolError("Image must be a PNG, JPEG, GIF, or WebP file");
};

/** Normalizes an emoji name the way the Java server did: spaces become underscores, and the name must be 2 to 32 letters, digits, or underscores. */
const normalizeEmojiName = (name: string): string => {
  const normalized = name.replace(/\s+/g, "_");
  if (normalized.length < 2 || normalized.length > 32)
    throw new ToolError("Emoji name must be between 2 and 32 characters");
  if (!/^[A-Za-z0-9_]+$/.test(normalized))
    throw new ToolError(
      "Emoji name can only contain letters, numbers, and underscores",
    );
  return normalized;
};

/** Splits a comma-separated list of role IDs, and drops the empty entries. */
const parseRoleIds = (roles: string): string[] =>
  roles
    .split(",")
    .map((id) => id.trim())
    .filter((id) => id.length > 0);

/** Fails with a ToolError when a role ID is not a role of the server. */
const checkRoles = async (ctx: Context, server: string, roleIds: string[]) => {
  if (roleIds.length === 0) return;
  const known = new Set(
    (await ctx.client.rest.guilds.getRoles(server)).map((r) => r.id),
  );
  const missing = roleIds.find((id) => !known.has(id));
  if (missing !== undefined) throw new ToolError(`Role not found: ${missing}`);
};

const emojiImageUrl = (emoji: { id: string; animated?: boolean }) =>
  `https://cdn.discordapp.com/emojis/${emoji.id}.${emoji.animated ? "gif" : "png"}`;

export const emojiTools = [
  defineTool({
    name: "list_emojis",
    title: "List emojis",
    description: "List custom emojis with ID, name, animated flag, and roles.",
    group: "emojis",
    access: "read",
    input: z.object({ guildId }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const emojis = await ctx.client.rest.guilds.getEmojis(id);
      return {
        emojis: emojis.map((emoji) => ({
          id: emoji.id,
          name: emoji.name,
          animated: emoji.animated ?? false,
          roleIds: emoji.roles,
        })),
      };
    },
  }),

  defineTool({
    name: "get_emoji_details",
    title: "Get emoji details",
    description:
      "Get a custom emoji: flags, image URL, and roles that may use it.",
    group: "emojis",
    access: "read",
    input: z.object({
      guildId,
      emojiId: snowflake("Emoji ID"),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const emoji = await ctx.client.rest.guilds.getEmoji(id, args.emojiId);
      return {
        id: emoji.id,
        name: emoji.name,
        animated: emoji.animated ?? false,
        managed: emoji.managed ?? false,
        available: emoji.available ?? true,
        imageUrl: emojiImageUrl(emoji),
        roleIds: emoji.roles,
      };
    },
  }),

  defineTool({
    name: "create_emoji",
    title: "Create emoji",
    description:
      "Upload a custom emoji from base64, an https URL, or a file path. PNG, JPEG, GIF, or WebP up to 256 KiB.",
    group: "emojis",
    access: "write",
    input: z.object({
      guildId,
      name: z.string().min(1).max(100).describe("2-32 letters, digits, or _"),
      image: z.string().optional().describe("Base64 or data URI"),
      imageUrl: z.string().max(2048).optional().describe("Public https URL"),
      filePath: z
        .string()
        .optional()
        .describe("Absolute path under DISCORD_MCP_FILE_ROOTS"),
      roles: z.string().optional().describe("Comma-separated role IDs"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const name = normalizeEmojiName(args.name);
      const file = await ctx.files.load({
        filePath: args.filePath,
        fileUrl: args.imageUrl,
        fileData: args.image,
        fileName: name,
      });
      if (file.contents.length > MAX_EMOJI_BYTES) {
        throw new ToolError(
          `Image is ${Math.ceil(file.contents.length / 1024)} KiB, over the 256 KiB limit for emojis`,
        );
      }
      const image = `data:${imageMimeType(file.contents)};base64,${file.contents.toString("base64")}`;
      const roleIds = parseRoleIds(args.roles ?? "");
      await checkRoles(ctx, id, roleIds);
      const emoji = await ctx.client.rest.guilds.createEmoji(id, {
        image,
        name,
        roles: roleIds,
        ...reasonOption(args.reason),
      });
      return { id: emoji.id, name: emoji.name };
    },
  }),

  defineTool({
    name: "edit_emoji",
    title: "Edit emoji",
    description:
      "Rename a custom emoji or change its roles. Empty roles means everyone.",
    group: "emojis",
    access: "write",
    input: z.object({
      guildId,
      emojiId: snowflake("Emoji ID"),
      name: z
        .string()
        .min(1)
        .max(100)
        .optional()
        .describe("2-32 letters, digits, or _"),
      roles: z.string().optional().describe("Comma-separated role IDs"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      // An empty roles string clears the restriction, which Oceanic sends as null.
      let roles: string[] | null | undefined;
      if (args.roles !== undefined) {
        const roleIds = parseRoleIds(args.roles);
        await checkRoles(ctx, id, roleIds);
        roles = roleIds.length === 0 ? null : roleIds;
      }
      const emoji = await ctx.client.rest.guilds.editEmoji(
        id,
        args.emojiId,
        definedOnly({
          name:
            args.name === undefined ? undefined : normalizeEmojiName(args.name),
          roles,
          ...reasonOption(args.reason),
        }),
      );
      return { id: emoji.id, name: emoji.name };
    },
  }),

  defineTool({
    name: "delete_emoji",
    title: "Delete emoji",
    description: "Delete a custom emoji.",
    group: "emojis",
    access: "destructive",
    input: z.object({
      guildId,
      emojiId: snowflake("Emoji ID"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const emoji = await ctx.client.rest.guilds.getEmoji(id, args.emojiId);
      await ctx.client.rest.guilds.deleteEmoji(id, args.emojiId, args.reason);
      return { deleted: true, id: emoji.id, name: emoji.name };
    },
  }),
];
