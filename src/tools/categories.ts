import { ChannelTypes } from "oceanic.js";
import { z } from "zod";
import { defineTool, type Tool, ToolError } from "../tool.ts";
import {
  describeChannel,
  findCategory,
  type GuildChannel,
  onChannel,
} from "./channels.ts";
import { auditReason, guildId, snowflake } from "./schemas.ts";
import { definedOnly } from "./util.ts";

const categoryName = z.string().min(1).max(100);

export const categoryTools: Tool[] = [
  defineTool({
    name: "create_category",
    title: "Create category",
    description: "Create a channel category.",
    group: "categories",
    access: "write",
    input: z.object({
      guildId,
      name: categoryName,
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channel = await ctx.client.rest.guilds.createChannel(
        ctx.guildId(args.guildId),
        ChannelTypes.GUILD_CATEGORY,
        { name: args.name, ...definedOnly({ reason: args.reason }) },
      );
      return describeChannel(channel);
    },
  }),
  defineTool({
    name: "delete_category",
    title: "Delete category",
    description: "Delete a channel category. Its channels are kept.",
    group: "categories",
    access: "destructive",
    input: z.object({
      guildId,
      categoryId: snowflake("Category ID"),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const category = findCategory(channels, args.categoryId);
      await onChannel(category.id, () =>
        ctx.client.rest.channels.delete(category.id, args.reason),
      );
      return { deleted: true, ...describeChannel(category) };
    },
  }),
  defineTool({
    name: "edit_category",
    title: "Edit category",
    description: "Rename or reorder a channel category.",
    group: "categories",
    access: "write",
    input: z.object({
      guildId,
      categoryId: snowflake("Category ID"),
      name: categoryName.optional(),
      position: z.number().int().min(0).optional(),
      reason: auditReason,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const category = findCategory(channels, args.categoryId);
      if (args.name === undefined && args.position === undefined)
        throw new ToolError(
          "At least one of name or position must be provided",
        );
      const edited = await onChannel(category.id, () =>
        ctx.client.rest.channels.edit<GuildChannel>(
          category.id,
          definedOnly({
            name: args.name,
            position: args.position,
            reason: args.reason,
          }),
        ),
      );
      return describeChannel(edited);
    },
  }),
  defineTool({
    name: "find_category",
    title: "Find category",
    description:
      "Find a channel category by name, case-insensitive. Fails on duplicate names.",
    group: "categories",
    access: "read",
    input: z.object({
      guildId,
      categoryName,
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const wanted = args.categoryName.toLowerCase();
      const matches = channels.filter(
        (c) =>
          c.type === ChannelTypes.GUILD_CATEGORY &&
          c.name.toLowerCase() === wanted,
      );
      const [category, ...others] = matches;
      if (!category)
        throw new ToolError(`Category ${args.categoryName} was not found`);
      if (others.length > 0)
        throw new ToolError(
          `Multiple categories are named ${args.categoryName}: ${matches
            .map((c) => `${c.name} (${c.id})`)
            .join(", ")}. Use the category ID instead.`,
        );
      return describeChannel(category);
    },
  }),
  defineTool({
    name: "list_channels_in_category",
    title: "List channels in category",
    description:
      "List the channels directly under a category, without threads.",
    group: "categories",
    access: "read",
    input: z.object({
      guildId,
      categoryId: snowflake("Category ID"),
    }),
    run: async (args, ctx) => {
      const channels = await ctx.client.rest.guilds.getChannels(
        ctx.guildId(args.guildId),
      );
      const category = findCategory(channels, args.categoryId);
      return channels
        .filter((c) => c.parentID === category.id)
        .map(describeChannel);
    },
  }),
];
