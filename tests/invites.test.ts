import { describe, expect, test } from "bun:test";
import { ChannelTypes } from "oceanic.js";
import { inviteTools } from "../src/tools/invites.ts";
import { callTool, DEFAULT_GUILD, fakeContext, recorder } from "./harness.ts";

const CHANNEL = "300000000000000003";
const CODE = "ABCde";

/** A guild channel shaped like the Oceanic channel that getChannels returns. */
const fakeChannel = (type: ChannelTypes = ChannelTypes.GUILD_TEXT) => ({
  id: CHANNEL,
  guildID: DEFAULT_GUILD,
  name: "general",
  type,
});

/** An invite shaped like the Oceanic invite that getInvite returns. */
const fakeInvite = (code: string) => ({
  code,
  channelID: CHANNEL,
  guild: null,
  uses: 0,
  maxUses: 0,
  maxAge: 0,
  temporary: false,
  approximateMemberCount: 3,
  approximatePresenceCount: 1,
});

describe("invite code parsing", () => {
  test.each([
    ["ABCde", CODE],
    ["https://discord.gg/ABCde", CODE],
    ["http://discord.gg/ABCde/", CODE],
    ["https://discord.com/invite/ABCde", CODE],
    ["  discord.gg/ABCde?event=1  ", CODE],
  ])("get_invite_details reads the code from %s", async (input, code) => {
    const getInvite = recorder(fakeInvite(code));
    const ctx = fakeContext({ channels: { getInvite } });

    const result = await callTool(inviteTools, ctx, "get_invite_details", {
      inviteCode: input,
    });

    expect(result.isError).toBe(false);
    expect(getInvite.calls[0]?.[0]).toBe(code);
  });

  test("delete_invite revokes the code of a URL", async () => {
    const deleteInvite = recorder(undefined);
    const ctx = fakeContext({ channels: { deleteInvite } });

    const result = await callTool(inviteTools, ctx, "delete_invite", {
      inviteCode: "https://discord.gg/ABCde",
    });

    expect(result.isError).toBe(false);
    expect(deleteInvite.calls[0]?.[0]).toBe(CODE);
  });

  test("a value that is not an invite fails before any REST call", async () => {
    const getInvite = recorder(fakeInvite(CODE));
    const ctx = fakeContext({ channels: { getInvite } });

    const result = await callTool(inviteTools, ctx, "get_invite_details", {
      inviteCode: "https://example.com/ABCde",
    });

    expect(result.isError).toBe(true);
    expect(getInvite.calls).toEqual([]);
  });
});

describe("create_invite", () => {
  test("passes the given options and the audit reason to Discord", async () => {
    const createInvite = recorder({
      code: "NEWcode",
      maxAge: 3600,
      maxUses: 5,
      temporary: true,
    });
    const ctx = fakeContext({
      channels: { createInvite },
      guilds: { getChannels: recorder([fakeChannel()]) },
    });

    const result = await callTool(inviteTools, ctx, "create_invite", {
      channelId: CHANNEL,
      maxAge: 3600,
      maxUses: 5,
      temporary: true,
      unique: true,
      reason: "weekly event",
    });

    expect(result.isError).toBe(false);
    expect(createInvite.calls[0]).toEqual([
      CHANNEL,
      {
        maxAge: 3600,
        maxUses: 5,
        temporary: true,
        unique: true,
        reason: "weekly event",
      },
    ]);
    expect(result.data).toMatchObject({ code: "NEWcode", maxAge: 3600 });
  });

  test("refuses a channel type that cannot hold invites", async () => {
    const createInvite = recorder(fakeInvite(CODE));
    const ctx = fakeContext({
      channels: { createInvite },
      guilds: {
        getChannels: recorder([fakeChannel(ChannelTypes.GUILD_CATEGORY)]),
      },
    });

    const result = await callTool(inviteTools, ctx, "create_invite", {
      channelId: CHANNEL,
    });

    expect(result.isError).toBe(true);
    expect(createInvite.calls).toEqual([]);
  });

  test("refuses a channel that is not in the server", async () => {
    const createInvite = recorder(fakeInvite(CODE));
    const ctx = fakeContext({
      channels: { createInvite },
      guilds: { getChannels: recorder([]) },
    });

    const result = await callTool(inviteTools, ctx, "create_invite", {
      channelId: CHANNEL,
    });

    expect(result.isError).toBe(true);
    expect(createInvite.calls).toEqual([]);
  });
});
