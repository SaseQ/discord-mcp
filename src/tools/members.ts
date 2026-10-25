import type { Member } from "oceanic.js";
import { z } from "zod";
import { defineTool, ToolError } from "../tool.ts";
import { guildId, snowflake } from "./schemas.ts";
import { hasStatus } from "./util.ts";

type Field = "id" | "username" | "globalName" | "nick";

interface Match {
  field: Field;
  /** True when the field equals the query, ignoring case. */
  exact: boolean;
}

interface Candidate {
  member: Member;
  matches: Match[];
  exact: boolean;
}

const SNOWFLAKE = /^\d{17,20}$/;

/** Ranks the members that Discord returned for a query, with exact matches first. */
const rankMembers = (members: Member[], query: string): Candidate[] => {
  const q = query.toLowerCase();
  return members
    .map((member): Candidate => {
      const fields: Array<[Field, string | null]> = [
        ["username", member.user.username],
        ["globalName", member.user.globalName],
        ["nick", member.nick],
      ];
      const matches = fields.flatMap(([field, value]): Match[] => {
        const v = value?.toLowerCase();
        if (!v?.includes(q)) return [];
        return [{ field, exact: v === q }];
      });
      return { member, matches, exact: matches.some((m) => m.exact) };
    })
    .sort((a, b) => Number(b.exact) - Number(a.exact));
};

const summarize = (candidate: Candidate) => ({
  userId: candidate.member.user.id,
  username: candidate.member.user.username,
  globalName: candidate.member.user.globalName,
  nick: candidate.member.nick,
  ...(candidate.member.user.bot ? { bot: true } : {}),
  matched: candidate.matches.map((m) => m.field),
  exact: candidate.exact,
});

/** Splits `name#discriminator` into its parts, and keeps a query such as `C#` or `Team #1a` whole. */
const splitUsername = (username: string) => {
  const match = /^(.*)#(\d{4}|0)$/.exec(username.trim());
  if (!match) return { name: username.trim(), discriminator: undefined };
  return { name: match[1]?.trim() ?? "", discriminator: match[2] };
};

export const memberTools = [
  defineTool({
    name: "get_member_by_id",
    title: "Get member by ID",
    description:
      "Get a server member by user ID: names, join date, roles with names, and the timeout end if one is active.",
    group: "members",
    access: "read",
    input: z.object({
      guildId,
      userId: snowflake("userId"),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const [member, roles] = await Promise.all([
        ctx.client.rest.guilds.getMember(id, args.userId),
        ctx.client.rest.guilds.getRoles(id),
      ]);
      const roleNames = new Map(roles.map((role) => [role.id, role.name]));
      return {
        userId: member.user.id,
        username: member.user.username,
        globalName: member.user.globalName,
        nick: member.nick,
        ...(member.user.bot ? { bot: true } : {}),
        joinedAt: member.joinedAt?.toISOString() ?? null,
        roles: member.roles.map((roleId) => ({
          id: roleId,
          name: roleNames.get(roleId) ?? null,
        })),
        timeoutUntil: member.communicationDisabledUntil?.toISOString() ?? null,
      };
    },
  }),

  defineTool({
    name: "search_members",
    title: "Search members",
    description:
      "Find server members, and their user IDs for <@id> mentions, by username, display name, nickname, name#discriminator, or user ID. Exact matches come first.",
    group: "members",
    access: "read",
    input: z.object({
      guildId,
      query: z.string().min(1),
      limit: z.number().int().min(1).max(100).default(25),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      if (SNOWFLAKE.test(args.query)) {
        try {
          const member = await ctx.client.rest.guilds.getMember(id, args.query);
          return {
            members: [
              summarize({
                member,
                matches: [{ field: "id", exact: true }],
                exact: true,
              }),
            ],
          };
        } catch (error) {
          // A 404 means no member has this ID, so the query is searched as a name.
          if (!hasStatus(error, 404)) throw error;
        }
      }
      const { name, discriminator } = splitUsername(args.query);
      if (!name)
        throw new ToolError("query needs a name before the #discriminator");
      const found = await ctx.client.rest.guilds.searchMembers(id, {
        query: name,
        // A discriminator filters after the search, so the search fetches the most that Discord allows.
        limit: discriminator ? 100 : args.limit,
      });
      const members = rankMembers(found, name)
        .filter(
          (c) =>
            !discriminator || c.member.user.discriminator === discriminator,
        )
        .slice(0, args.limit);
      return { members: members.map(summarize) };
    },
  }),

  defineTool({
    name: "list_guild_members",
    title: "List server members",
    description:
      "List server members, one page at a time; pass nextAfter as after for the next page. Needs the Server Members privileged intent.",
    group: "members",
    access: "read",
    input: z.object({
      guildId,
      limit: z.number().int().min(1).max(1000).default(100),
      after: snowflake("after").optional(),
    }),
    run: async (args, ctx) => {
      const id = ctx.guildId(args.guildId);
      const members = await ctx.client.rest.guilds
        .getMembers(id, {
          limit: args.limit,
          ...(args.after ? { after: args.after } : {}),
        })
        .catch((error: unknown) => {
          if (hasStatus(error, 403))
            throw new ToolError(
              "Discord refused the member list. Enable the Server Members privileged intent in the Discord Developer Portal, under the bot settings, and try again.",
            );
          throw error;
        });
      const last = members.at(-1);
      return {
        members: members.map((member) => ({
          userId: member.user.id,
          username: member.user.username,
          globalName: member.user.globalName,
          nick: member.nick,
          ...(member.user.bot ? { bot: true } : {}),
          joinedAt: member.joinedAt?.toISOString() ?? null,
        })),
        nextAfter: members.length === args.limit && last ? last.user.id : null,
      };
    },
  }),
];
