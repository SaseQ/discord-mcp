import { describe, expect, mock, test } from "bun:test";
import { ChannelTypes } from "oceanic.js";
import { channelTools } from "../src/tools/channels.ts";
import { moderationTools } from "../src/tools/moderation.ts";
import { voiceTools } from "../src/tools/voice.ts";
import { callTool, DEFAULT_GUILD, fakeContext, restError } from "./harness.ts";

const USER = "400000000000000001";
const VOICE = "500000000000000001";
const OTHER_VOICE = "500000000000000003";
const TEXT = "500000000000000002";
const CATEGORY = "600000000000000001";

const channel = (id: string, type: ChannelTypes, name = "lobby") => ({
  id,
  name,
  type,
});

const guildChannels = () => [
  channel(VOICE, ChannelTypes.GUILD_VOICE),
  channel(TEXT, ChannelTypes.GUILD_TEXT, "general"),
  channel(CATEGORY, ChannelTypes.GUILD_CATEGORY, "Voice"),
];

describe("moderate_member move", () => {
  test("rejects a target channel that is not a voice or stage channel", async () => {
    const editMember = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels(), editMember },
    });

    const result = await callTool(moderationTools, ctx, "moderate_member", {
      action: "move",
      userId: USER,
      channelId: TEXT,
    });

    expect(result.isError).toBe(true);
    expect(editMember).not.toHaveBeenCalled();
  });

  test("moves a connected member to the target voice channel", async () => {
    const editMember = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => guildChannels(),
        getVoiceState: async () => ({ channelID: OTHER_VOICE }),
        editMember,
      },
    });

    const result = await callTool(moderationTools, ctx, "moderate_member", {
      action: "move",
      userId: USER,
      channelId: VOICE,
    });

    expect(result.isError).toBe(false);
    expect(editMember).toHaveBeenCalledWith(DEFAULT_GUILD, USER, {
      channelID: VOICE,
    });
  });

  test("rejects a member who is not in a voice channel", async () => {
    const editMember = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => guildChannels(),
        getVoiceState: async () => {
          throw restError(404, 10065, "GET");
        },
        editMember,
      },
    });

    const result = await callTool(moderationTools, ctx, "moderate_member", {
      action: "move",
      userId: USER,
      channelId: VOICE,
    });

    expect(result.isError).toBe(true);
    expect(editMember).not.toHaveBeenCalled();
  });
});

describe("moderate_member disconnect", () => {
  test("sends a null channel to disconnect the member", async () => {
    const editMember = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getVoiceState: async () => ({ channelID: VOICE }),
        editMember,
      },
    });

    const result = await callTool(moderationTools, ctx, "moderate_member", {
      action: "disconnect",
      userId: USER,
    });

    expect(result.isError).toBe(false);
    expect(editMember).toHaveBeenCalledWith(DEFAULT_GUILD, USER, {
      channelID: null,
    });
  });
});

describe("modify_voice_state", () => {
  test("server mutes a connected member", async () => {
    const editMember = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getVoiceState: async () => ({ channelID: VOICE }),
        editMember,
      },
    });

    const result = await callTool(voiceTools, ctx, "modify_voice_state", {
      userId: USER,
      mute: true,
    });

    expect(result.isError).toBe(false);
    expect(editMember).toHaveBeenCalledWith(DEFAULT_GUILD, USER, {
      mute: true,
    });
  });
});

describe("create_channel voice", () => {
  test("rejects a category ID that is not a category of the server", async () => {
    const createChannel = mock(async () =>
      channel(VOICE, ChannelTypes.GUILD_VOICE),
    );
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels(), createChannel },
    });

    const result = await callTool(channelTools, ctx, "create_channel", {
      type: "voice",
      name: "music",
      categoryId: TEXT,
    });

    expect(result.isError).toBe(true);
    expect(createChannel).not.toHaveBeenCalled();
  });

  test("creates a voice channel under the category with the user limit", async () => {
    const createChannel = mock(async () =>
      channel(VOICE, ChannelTypes.GUILD_VOICE, "music"),
    );
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels(), createChannel },
    });

    const result = await callTool(channelTools, ctx, "create_channel", {
      type: "voice",
      name: "music",
      categoryId: CATEGORY,
      userLimit: 5,
    });

    expect(result.isError).toBe(false);
    expect(createChannel).toHaveBeenCalledWith(
      DEFAULT_GUILD,
      ChannelTypes.GUILD_VOICE,
      { name: "music", parentID: CATEGORY, userLimit: 5 },
    );
    expect(result.data).toEqual({ id: VOICE, name: "music", type: "voice" });
  });
});

describe("create_channel stage type", () => {
  test("creates a stage channel with the bitrate", async () => {
    const createChannel = mock(async () =>
      channel(VOICE, ChannelTypes.GUILD_STAGE_VOICE, "town hall"),
    );
    const ctx = fakeContext({
      guilds: { getChannels: async () => guildChannels(), createChannel },
    });

    const result = await callTool(channelTools, ctx, "create_channel", {
      name: "town hall",
      type: "stage",
      bitrate: 64000,
    });

    expect(result.isError).toBe(false);
    expect(createChannel).toHaveBeenCalledWith(
      DEFAULT_GUILD,
      ChannelTypes.GUILD_STAGE_VOICE,
      { name: "town hall", bitrate: 64000 },
    );
    expect(result.data).toEqual({
      id: VOICE,
      name: "town hall",
      type: "stage",
    });
  });

  test("rejects a user limit on a stage channel", async () => {
    const createChannel = mock(async () =>
      channel(VOICE, ChannelTypes.GUILD_STAGE_VOICE),
    );
    const ctx = fakeContext({ guilds: { createChannel } });

    const result = await callTool(channelTools, ctx, "create_channel", {
      name: "town hall",
      type: "stage",
      userLimit: 5,
    });

    expect(result.isError).toBe(true);
    expect(createChannel).not.toHaveBeenCalled();
  });
});

describe("edit_voice_channel", () => {
  test("an empty region sets the automatic region, which is null", async () => {
    const edit = mock(async () => channel(VOICE, ChannelTypes.GUILD_VOICE));
    const ctx = fakeContext({
      channels: {
        get: async () => channel(VOICE, ChannelTypes.GUILD_VOICE),
        edit,
      },
    });

    const result = await callTool(voiceTools, ctx, "edit_voice_channel", {
      channelId: VOICE,
      rtcRegion: "",
    });

    expect(result.isError).toBe(false);
    expect(edit).toHaveBeenCalledWith(VOICE, { rtcRegion: null });
  });

  test("rejects a channel that is not a voice or stage channel", async () => {
    const edit = mock(async () => channel(TEXT, ChannelTypes.GUILD_TEXT));
    const ctx = fakeContext({
      channels: {
        get: async () => channel(TEXT, ChannelTypes.GUILD_TEXT, "general"),
        edit,
      },
    });

    const result = await callTool(voiceTools, ctx, "edit_voice_channel", {
      channelId: TEXT,
      name: "renamed",
    });

    expect(result.isError).toBe(true);
    expect(edit).not.toHaveBeenCalled();
  });
});
