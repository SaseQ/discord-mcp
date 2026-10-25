import { describe, expect, test } from "bun:test";
import { roleTools } from "../src/tools/roles.ts";
import { callTool, DEFAULT_GUILD, fakeContext, recorder } from "./harness.ts";

const USER = "100000000000000001";
const ROLE = "200000000000000002";

/** A role shaped like the Oceanic role that the REST routes return. */
const fakeRole = (fields: { id?: string; name?: string } = {}) => ({
  id: fields.id ?? ROLE,
  name: fields.name ?? "moderators",
  colors: { primaryColor: 16711680, secondaryColor: null, tertiaryColor: null },
  position: 3,
  hoist: true,
  mentionable: false,
  permissions: { allow: 8n },
});

/** A guild member shaped like the Oceanic member that getMember returns. */
const fakeMemberOf = (username: string) => ({ user: { username } });

/** A context whose guild routes are the given fake functions. */
const guildsContext = (guilds: Record<string, unknown>) =>
  fakeContext({ guilds });

describe("@everyone guards", () => {
  const everyone = DEFAULT_GUILD;

  test("edit_role rejects the role whose ID is the guild ID before any REST call", async () => {
    const editRole = recorder(fakeRole());
    const getRoles = recorder([fakeRole({ id: everyone })]);
    const ctx = guildsContext({ editRole, getRoles });

    const result = await callTool(roleTools, ctx, "edit_role", {
      guildId: DEFAULT_GUILD,
      roleId: everyone,
      name: "renamed",
    });

    expect(result.isError).toBe(true);
    expect(editRole.calls).toEqual([]);
    expect(getRoles.calls).toEqual([]);
  });

  test("delete_role rejects the role whose ID is the guild ID before any REST call", async () => {
    const deleteRole = recorder(undefined);
    const getRoles = recorder([fakeRole({ id: everyone })]);
    const ctx = guildsContext({ deleteRole, getRoles });

    const result = await callTool(roleTools, ctx, "delete_role", {
      guildId: DEFAULT_GUILD,
      roleId: everyone,
    });

    expect(result.isError).toBe(true);
    expect(deleteRole.calls).toEqual([]);
    expect(getRoles.calls).toEqual([]);
  });

  test("set_member_role add rejects the role whose ID is the guild ID before any REST call", async () => {
    const addMemberRole = recorder(undefined);
    const getMember = recorder(fakeMemberOf("alex"));
    const getRoles = recorder([fakeRole({ id: everyone })]);
    const ctx = guildsContext({ addMemberRole, getMember, getRoles });

    const result = await callTool(roleTools, ctx, "set_member_role", {
      guildId: DEFAULT_GUILD,
      userId: USER,
      roleId: everyone,
      action: "add",
    });

    expect(result.isError).toBe(true);
    expect(addMemberRole.calls).toEqual([]);
    expect(getMember.calls).toEqual([]);
    expect(getRoles.calls).toEqual([]);
  });

  test("set_member_role remove rejects the role whose ID is the guild ID before any REST call", async () => {
    const removeMemberRole = recorder(undefined);
    const getMember = recorder(fakeMemberOf("alex"));
    const getRoles = recorder([fakeRole({ id: everyone })]);
    const ctx = guildsContext({ removeMemberRole, getMember, getRoles });

    const result = await callTool(roleTools, ctx, "set_member_role", {
      guildId: DEFAULT_GUILD,
      userId: USER,
      roleId: everyone,
      action: "remove",
    });

    expect(result.isError).toBe(true);
    expect(removeMemberRole.calls).toEqual([]);
    expect(getMember.calls).toEqual([]);
    expect(getRoles.calls).toEqual([]);
  });
});

describe("list_roles", () => {
  test("reports the primary color of the role as a number", async () => {
    const ctx = guildsContext({ getRoles: recorder([fakeRole()]) });

    const result = await callTool(roleTools, ctx, "list_roles", {});

    expect(result.isError).toBe(false);
    expect(result.data).toEqual({
      roles: [
        {
          id: ROLE,
          name: "moderators",
          color: 16711680,
          position: 3,
          hoist: true,
          mentionable: false,
          permissions: "8",
        },
      ],
    });
  });
});

describe("edit_role", () => {
  test("sends only the fields that were given", async () => {
    const editRole = recorder(fakeRole());
    const ctx = guildsContext({ editRole });

    const result = await callTool(roleTools, ctx, "edit_role", {
      guildId: DEFAULT_GUILD,
      roleId: ROLE,
      name: "renamed",
      hoist: false,
    });

    expect(result.isError).toBe(false);
    expect(editRole.calls.length).toBe(1);
    const [guildId, roleId, options] = editRole.calls[0] ?? [];
    expect(guildId).toBe(DEFAULT_GUILD);
    expect(roleId).toBe(ROLE);
    expect(Object.keys(options as object).sort()).toEqual(["hoist", "name"]);
    expect(options).toEqual({ name: "renamed", hoist: false });
  });

  test("passes the reason to Oceanic in the options", async () => {
    const editRole = recorder(fakeRole());
    const ctx = guildsContext({ editRole });

    await callTool(roleTools, ctx, "edit_role", {
      guildId: DEFAULT_GUILD,
      roleId: ROLE,
      mentionable: true,
      reason: "audit note",
    });

    expect(editRole.calls[0]?.[2]).toEqual({
      mentionable: true,
      reason: "audit note",
    });
  });
});

describe("create_role", () => {
  test("uses the defaults for the fields that were not given", async () => {
    const createRole = recorder(fakeRole());
    const ctx = guildsContext({ createRole });

    const result = await callTool(roleTools, ctx, "create_role", {
      guildId: DEFAULT_GUILD,
      name: "helpers",
    });

    expect(result.isError).toBe(false);
    expect(createRole.calls[0]?.[0]).toBe(DEFAULT_GUILD);
    expect(createRole.calls[0]?.[1]).toEqual({
      name: "helpers",
      color: 0,
      hoist: false,
      mentionable: false,
      permissions: "0",
    });
  });

  test("passes the given fields and the reason to Oceanic", async () => {
    const createRole = recorder(fakeRole());
    const ctx = guildsContext({ createRole });

    await callTool(roleTools, ctx, "create_role", {
      guildId: DEFAULT_GUILD,
      name: "helpers",
      color: 255,
      hoist: true,
      mentionable: true,
      permissions: "1024",
      reason: "new team",
    });

    expect(createRole.calls[0]?.[1]).toEqual({
      name: "helpers",
      color: 255,
      hoist: true,
      mentionable: true,
      permissions: "1024",
      reason: "new team",
    });
  });
});

describe("delete_role", () => {
  test("deletes the role by its ID and passes the reason as the third argument", async () => {
    const deleteRole = recorder(undefined);
    const ctx = guildsContext({
      deleteRole,
      getRoles: recorder([fakeRole()]),
    });

    const result = await callTool(roleTools, ctx, "delete_role", {
      guildId: DEFAULT_GUILD,
      roleId: ROLE,
      reason: "cleanup",
    });

    expect(result.isError).toBe(false);
    expect(deleteRole.calls).toEqual([[DEFAULT_GUILD, ROLE, "cleanup"]]);
  });
});

describe("set_member_role add", () => {
  test("adds the role to the member with the right IDs", async () => {
    const addMemberRole = recorder(undefined);
    const ctx = guildsContext({
      addMemberRole,
      getMember: recorder(fakeMemberOf("alex")),
      getRoles: recorder([fakeRole()]),
    });

    const result = await callTool(roleTools, ctx, "set_member_role", {
      guildId: DEFAULT_GUILD,
      userId: USER,
      roleId: ROLE,
      action: "add",
    });

    expect(result.isError).toBe(false);
    expect(addMemberRole.calls.length).toBe(1);
    expect(addMemberRole.calls[0]?.slice(0, 3)).toEqual([
      DEFAULT_GUILD,
      USER,
      ROLE,
    ]);
  });

  test("passes the reason as the fourth argument", async () => {
    const addMemberRole = recorder(undefined);
    const ctx = guildsContext({
      addMemberRole,
      getMember: recorder(fakeMemberOf("alex")),
      getRoles: recorder([fakeRole()]),
    });

    await callTool(roleTools, ctx, "set_member_role", {
      guildId: DEFAULT_GUILD,
      userId: USER,
      roleId: ROLE,
      action: "add",
      reason: "promotion",
    });

    expect(addMemberRole.calls[0]?.[3]).toBe("promotion");
  });
});

describe("set_member_role remove", () => {
  test("removes the role from the member with the right IDs", async () => {
    const removeMemberRole = recorder(undefined);
    const ctx = guildsContext({
      removeMemberRole,
      getMember: recorder(fakeMemberOf("alex")),
      getRoles: recorder([fakeRole()]),
    });

    const result = await callTool(roleTools, ctx, "set_member_role", {
      guildId: DEFAULT_GUILD,
      userId: USER,
      roleId: ROLE,
      action: "remove",
      reason: "left team",
    });

    expect(result.isError).toBe(false);
    expect(removeMemberRole.calls.length).toBe(1);
    expect(removeMemberRole.calls[0]).toEqual([
      DEFAULT_GUILD,
      USER,
      ROLE,
      "left team",
    ]);
  });
});
