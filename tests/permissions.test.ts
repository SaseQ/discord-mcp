import { describe, expect, mock, test } from "bun:test";
import { ChannelTypes, OverwriteTypes, Permissions } from "oceanic.js";
import { permissionTools } from "../src/tools/permissions.ts";
import { callTool, DEFAULT_GUILD, fakeContext, recorder } from "./harness.ts";

const CHANNEL = "200000000000000001";
const ROLE = "300000000000000001";
const USER = "400000000000000001";

interface FakeOverwrite {
  id: string;
  type: OverwriteTypes;
  allow: bigint;
  deny: bigint;
}

const textChannel = (overwrites: FakeOverwrite[] = []) => ({
  id: CHANNEL,
  name: "general",
  type: ChannelTypes.GUILD_TEXT,
  permissionOverwrites: new Map(overwrites.map((o) => [o.id, o] as const)),
});

const sorted = (names: unknown) => [...(names as string[])].sort();

describe("list_channel_permission_overwrites", () => {
  test("decodes the bitfields of each overwrite into names", async () => {
    const overwrite: FakeOverwrite = {
      id: ROLE,
      type: OverwriteTypes.ROLE,
      allow: Permissions.VIEW_CHANNEL | Permissions.CONNECT,
      deny: Permissions.SPEAK,
    };
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [textChannel([overwrite])],
      },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "list_channel_permission_overwrites",
      { channelId: CHANNEL },
    );

    expect(result.isError).toBe(false);
    const [listed] = (result.data as { overwrites: unknown[] }).overwrites as {
      id: string;
      type: string;
      allow: { bits: string; names: string[] };
      deny: { bits: string; names: string[] };
    }[];
    expect(listed).toMatchObject({ id: ROLE, type: "role" });
    expect(listed?.allow.bits).toBe(
      (Permissions.VIEW_CHANNEL | Permissions.CONNECT).toString(),
    );
    expect(sorted(listed?.allow.names)).toEqual(["CONNECT", "VIEW_CHANNEL"]);
    expect(listed?.deny.names).toEqual(["SPEAK"]);
  });
});

describe("upsert_channel_permissions", () => {
  test("round trips permission names through the bitfields", async () => {
    const edit = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [textChannel()],
        getRoles: async () => [{ id: ROLE }],
      },
      channels: { editPermission: edit },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: ROLE,
        allowPermissions: "VIEW_CHANNEL,CONNECT",
        denyPermissions: "SPEAK",
      },
    );

    expect(result.isError).toBe(false);
    expect(edit).toHaveBeenCalledWith(CHANNEL, ROLE, {
      type: OverwriteTypes.ROLE,
      allow: Permissions.VIEW_CHANNEL | Permissions.CONNECT,
      deny: Permissions.SPEAK,
    });
    const allow = (result.data as { allow: { names: string[] } }).allow;
    expect(sorted(allow.names)).toEqual(["CONNECT", "VIEW_CHANNEL"]);
  });

  test("sends a missing deny side as 0 and a role overwrite", async () => {
    const edit = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [textChannel()],
        getRoles: async () => [{ id: ROLE }],
      },
      channels: { editPermission: edit },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: ROLE,
        allowRaw: "1024",
      },
    );

    expect(result.isError).toBe(false);
    expect(edit).toHaveBeenCalledWith(CHANNEL, ROLE, {
      type: OverwriteTypes.ROLE,
      allow: 1024n,
      deny: 0n,
    });
  });

  test("rejects a permission name that Discord does not define before any REST call", async () => {
    const getChannels = recorder([textChannel()]);
    const getRoles = recorder([{ id: ROLE }]);
    const edit = recorder(undefined);
    const ctx = fakeContext({
      guilds: { getChannels, getRoles },
      channels: { editPermission: edit },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: ROLE,
        allowPermissions: "VIEW_CHANNEL,MESSAGE_SEND",
      },
    );

    expect(result.isError).toBe(true);
    expect(result.data).toContain("MESSAGE_SEND");
    expect(getChannels.calls).toEqual([]);
    expect(getRoles.calls).toEqual([]);
    expect(edit.calls).toEqual([]);
  });

  test("rejects an allow flag that is also a deny flag", async () => {
    const edit = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [textChannel()],
        getRoles: async () => [{ id: ROLE }],
      },
      channels: { editPermission: edit },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: ROLE,
        allowPermissions: "SPEAK",
        denyPermissions: "SPEAK",
      },
    );

    expect(result.isError).toBe(true);
    expect(edit).not.toHaveBeenCalled();
  });
});

describe("upsert_channel_permissions for a member", () => {
  test("sends a missing allow side as 0 and a member overwrite", async () => {
    const edit = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: { getChannels: async () => [textChannel()] },
      channels: { editPermission: edit },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "member",
        targetId: USER,
        denyPermissions: "SPEAK",
      },
    );

    expect(result.isError).toBe(false);
    expect(edit).toHaveBeenCalledWith(CHANNEL, USER, {
      type: OverwriteTypes.MEMBER,
      allow: 0n,
      deny: Permissions.SPEAK,
    });
  });
});

describe("upsert_channel_permissions merge mode", () => {
  const everyone = (allow: bigint, deny: bigint): FakeOverwrite => ({
    id: DEFAULT_GUILD,
    type: OverwriteTypes.ROLE,
    allow,
    deny,
  });
  const contextWith = (overwrite: FakeOverwrite, edit: unknown) =>
    fakeContext({
      guilds: {
        getChannels: async () => [textChannel([overwrite])],
        getRoles: async () => [{ id: DEFAULT_GUILD }],
      },
      channels: { editPermission: edit },
    });

  test("locks read-only without losing the old deny, and unlocks again", async () => {
    const edit = mock(async () => undefined);
    const lock = await callTool(
      permissionTools,
      contextWith(
        everyone(Permissions.CONNECT, Permissions.VIEW_CHANNEL),
        edit,
      ),
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: DEFAULT_GUILD,
        denyPermissions: "SEND_MESSAGES",
        merge: true,
      },
    );

    expect(lock.isError).toBe(false);
    expect(edit).toHaveBeenCalledWith(CHANNEL, DEFAULT_GUILD, {
      type: OverwriteTypes.ROLE,
      allow: Permissions.CONNECT,
      deny: 3072n,
    });

    const unlockEdit = mock(async () => undefined);
    const unlock = await callTool(
      permissionTools,
      contextWith(everyone(Permissions.CONNECT, 3072n), unlockEdit),
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: DEFAULT_GUILD,
        clearPermissions: "SEND_MESSAGES",
        merge: true,
      },
    );

    expect(unlock.isError).toBe(false);
    expect(unlockEdit).toHaveBeenCalledWith(CHANNEL, DEFAULT_GUILD, {
      type: OverwriteTypes.ROLE,
      allow: Permissions.CONNECT,
      deny: 1024n,
    });
  });

  test("rejects clearPermissions without merge and edits nothing", async () => {
    const edit = mock(async () => undefined);
    const result = await callTool(
      permissionTools,
      contextWith(everyone(0n, 1024n), edit),
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: DEFAULT_GUILD,
        clearPermissions: "SEND_MESSAGES",
      },
    );

    expect(result.isError).toBe(true);
    expect(edit).not.toHaveBeenCalled();
  });

  test("rejects a permission both allowed and cleared, and edits nothing", async () => {
    const edit = mock(async () => undefined);
    const result = await callTool(
      permissionTools,
      contextWith(everyone(0n, 1024n), edit),
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: DEFAULT_GUILD,
        allowPermissions: "SEND_MESSAGES",
        clearPermissions: "SEND_MESSAGES",
        merge: true,
      },
    );

    expect(result.isError).toBe(true);
    expect(edit).not.toHaveBeenCalled();
  });

  test("rejects a merge that gives nothing", async () => {
    const edit = mock(async () => undefined);
    const result = await callTool(
      permissionTools,
      contextWith(everyone(0n, 1024n), edit),
      "upsert_channel_permissions",
      {
        channelId: CHANNEL,
        targetType: "role",
        targetId: DEFAULT_GUILD,
        merge: true,
      },
    );

    expect(result.isError).toBe(true);
    expect(edit).not.toHaveBeenCalled();
  });
});

describe("delete_channel_permission_overwrite", () => {
  test("rejects a target whose overwrite is of the other type", async () => {
    const deletePermission = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [
          textChannel([
            { id: ROLE, type: OverwriteTypes.ROLE, allow: 0n, deny: 0n },
          ]),
        ],
      },
      channels: { deletePermission },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "delete_channel_permission_overwrite",
      { channelId: CHANNEL, targetType: "member", targetId: ROLE },
    );

    expect(result.isError).toBe(true);
    expect(deletePermission).not.toHaveBeenCalled();
  });

  test("deletes the overwrite of the given role in the server", async () => {
    const deletePermission = mock(async () => undefined);
    const ctx = fakeContext({
      guilds: {
        getChannels: async () => [
          textChannel([
            { id: ROLE, type: OverwriteTypes.ROLE, allow: 0n, deny: 0n },
          ]),
        ],
      },
      channels: { deletePermission },
    });

    const result = await callTool(
      permissionTools,
      ctx,
      "delete_channel_permission_overwrite",
      {
        guildId: DEFAULT_GUILD,
        channelId: CHANNEL,
        targetType: "role",
        targetId: ROLE,
        reason: "cleanup",
      },
    );

    expect(result.isError).toBe(false);
    expect(deletePermission).toHaveBeenCalledWith(CHANNEL, ROLE, "cleanup");
  });
});
