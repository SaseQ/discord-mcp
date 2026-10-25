import { ChannelTypes } from "oceanic.js";
import { z } from "zod";
import { defineTool } from "../tool.ts";
import { guildId } from "./schemas.ts";

export const serverTools = [
  defineTool({
    name: "get_server_info",
    title: "Get server info",
    description:
      "Get server info: owner, member and channel counts, boosts, features.",
    group: "server",
    access: "read",
    input: z.object({ guildId }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const [guild, channels] = await Promise.all([
        ctx.client.rest.guilds.get(id, true),
        ctx.client.rest.guilds.getChannels(id),
      ]);
      const count = (...types: ChannelTypes[]) =>
        channels.filter((c) => types.includes(c.type)).length;
      return {
        id: guild.id,
        name: guild.name,
        description: guild.description,
        ownerId: guild.ownerID,
        createdAt: guild.createdAt.toISOString(),
        memberCount: guild.approximateMemberCount,
        onlineCount: guild.approximatePresenceCount,
        channels: {
          text: count(ChannelTypes.GUILD_TEXT, ChannelTypes.GUILD_ANNOUNCEMENT),
          voice: count(
            ChannelTypes.GUILD_VOICE,
            ChannelTypes.GUILD_STAGE_VOICE,
          ),
          forum: count(ChannelTypes.GUILD_FORUM, ChannelTypes.GUILD_MEDIA),
          categories: count(ChannelTypes.GUILD_CATEGORY),
        },
        boost: {
          tier: guild.premiumTier,
          count: guild.premiumSubscriptionCount ?? 0,
        },
        verificationLevel: guild.verificationLevel,
        preferredLocale: guild.preferredLocale,
        features: guild.features,
      };
    },
  }),
];
