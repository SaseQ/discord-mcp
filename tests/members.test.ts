import { describe, expect, test } from "bun:test";
import { memberTools } from "../src/tools/members.ts";
import {
  callTool,
  fakeContext,
  fakeMember,
  fakeRole,
  restError,
} from "./members-harness.ts";

describe("search_members by name", () => {
  test("ranks exact matches first and reports the field that matched", async () => {
    const found = [
      fakeMember({ id: "100000000000000001", username: "alexander" }),
      fakeMember({ id: "100000000000000002", username: "x", nick: "Alex" }),
      fakeMember({
        id: "100000000000000003",
        username: "zed",
        globalName: "alexandra",
      }),
    ];
    const ctx = fakeContext({ searchMembers: async () => found });

    const result = await callTool(memberTools, ctx, "search_members", {
      query: "alex",
    });

    expect(result.isError).toBe(false);
    const members = (
      result.data as {
        members: Array<{ userId: string; exact: boolean; matched: string[] }>;
      }
    ).members;
    expect(members.map((c) => c.userId)).toEqual([
      "100000000000000002",
      "100000000000000001",
      "100000000000000003",
    ]);
    expect(members[0]?.exact).toBe(true);
    expect(members[0]?.matched).toEqual(["nick"]);
    expect(members[2]?.exact).toBe(false);
    expect(members[2]?.matched).toEqual(["globalName"]);
  });

  test("searches by the name without the discriminator, then filters by it", async () => {
    const queries: unknown[] = [];
    const ctx = fakeContext({
      searchMembers: async (_guild: string, options: unknown) => {
        queries.push(options);
        return [
          fakeMember({
            id: "100000000000000001",
            username: "alex",
            discriminator: "1234",
          }),
          fakeMember({
            id: "100000000000000002",
            username: "alex",
            discriminator: "9999",
          }),
        ];
      },
    });

    const result = await callTool(memberTools, ctx, "search_members", {
      query: "alex#1234",
    });

    expect(queries).toEqual([{ query: "alex", limit: 100 }]);
    const members = (result.data as { members: Array<{ userId: string }> })
      .members;
    expect(members.map((c) => c.userId)).toEqual(["100000000000000001"]);
  });

  test.each(["Team #1a", "C#"])(
    "searches %s whole when the part after # is no discriminator",
    async (query) => {
      const queries: unknown[] = [];
      const ctx = fakeContext({
        searchMembers: async (_guild: string, options: unknown) => {
          queries.push(options);
          return [];
        },
      });

      await callTool(memberTools, ctx, "search_members", { query });

      expect(queries).toEqual([{ query, limit: 25 }]);
    },
  );

  test("returns no members when none matches", async () => {
    const ctx = fakeContext({ searchMembers: async () => [] });

    const result = await callTool(memberTools, ctx, "search_members", {
      query: "nobody",
    });

    expect(result.isError).toBe(false);
    expect(result.data).toEqual({ members: [] });
  });
});

describe("get_member_by_id", () => {
  test("returns the names, the role names, and the timeout end time", async () => {
    const until = new Date("2030-01-02T03:04:05.000Z");
    const ctx = fakeContext({
      getMember: async () =>
        fakeMember({
          id: "100000000000000001",
          username: "alex",
          globalName: "Alex G",
          nick: null,
          roles: ["200000000000000001", "200000000000000009"],
          joinedAt: new Date("2024-05-06T07:08:09.000Z"),
          communicationDisabledUntil: until,
        }),
      getRoles: async () => [
        fakeRole({ id: "200000000000000001", name: "Mod" }),
      ],
    });

    const result = await callTool(memberTools, ctx, "get_member_by_id", {
      userId: "100000000000000001",
    });

    expect(result.data).toEqual({
      userId: "100000000000000001",
      username: "alex",
      globalName: "Alex G",
      joinedAt: "2024-05-06T07:08:09.000Z",
      roles: [
        { id: "200000000000000001", name: "Mod" },
        { id: "200000000000000009" },
      ],
      timeoutUntil: "2030-01-02T03:04:05.000Z",
    });
  });

  test("reports no timeout when none is active", async () => {
    const ctx = fakeContext({
      getMember: async () =>
        fakeMember({ id: "100000000000000001", username: "alex" }),
      getRoles: async () => [],
    });

    const result = await callTool(memberTools, ctx, "get_member_by_id", {
      userId: "100000000000000001",
    });

    expect(
      (result.data as { timeoutUntil: unknown }).timeoutUntil,
    ).toBeUndefined();
  });
});

describe("search_members", () => {
  test("looks up a snowflake query with getMember and skips the search", async () => {
    let searched = false;
    const ctx = fakeContext({
      getMember: async (_guild: string, id: string) =>
        fakeMember({ id, username: "alex" }),
      searchMembers: async () => {
        searched = true;
        return [];
      },
    });

    const result = await callTool(memberTools, ctx, "search_members", {
      query: "100000000000000001",
      limit: 25,
    });

    expect(searched).toBe(false);
    const members = (
      result.data as { members: Array<{ userId: string; exact: boolean }> }
    ).members;
    expect(members.map((m) => [m.userId, m.exact])).toEqual([
      ["100000000000000001", true],
    ]);
  });

  test("searches by name when a snowflake query has no member", async () => {
    const queries: unknown[] = [];
    const ctx = fakeContext({
      getMember: async () => {
        throw restError(404);
      },
      searchMembers: async (_guild: string, options: unknown) => {
        queries.push(options);
        return [
          fakeMember({
            id: "100000000000000007",
            username: "123456789012345678",
          }),
        ];
      },
    });

    const result = await callTool(memberTools, ctx, "search_members", {
      query: "123456789012345678",
      limit: 10,
    });

    expect(queries).toEqual([{ query: "123456789012345678", limit: 10 }]);
    expect((result.data as { members: unknown[] }).members).toHaveLength(1);
  });

  test("searches by name with the given limit", async () => {
    const queries: unknown[] = [];
    const ctx = fakeContext({
      searchMembers: async (_guild: string, options: unknown) => {
        queries.push(options);
        return [];
      },
    });

    await callTool(memberTools, ctx, "search_members", {
      query: "alex",
      limit: 40,
    });

    expect(queries).toEqual([{ query: "alex", limit: 40 }]);
  });
});

describe("list_guild_members", () => {
  test("returns nextAfter as the last user ID when the page is full", async () => {
    const page = [
      fakeMember({ id: "100000000000000001", username: "a" }),
      fakeMember({ id: "100000000000000002", username: "b" }),
    ];
    const ctx = fakeContext({ getMembers: async () => page });

    const result = await callTool(memberTools, ctx, "list_guild_members", {
      limit: 2,
    });

    expect((result.data as { nextAfter: string }).nextAfter).toBe(
      "100000000000000002",
    );
  });

  test("returns no nextAfter on a partial page", async () => {
    const ctx = fakeContext({
      getMembers: async () => [
        fakeMember({ id: "100000000000000001", username: "a" }),
      ],
    });

    const result = await callTool(memberTools, ctx, "list_guild_members", {
      limit: 2,
    });

    expect((result.data as { nextAfter: unknown }).nextAfter).toBeUndefined();
  });

  test("passes the cursor to Discord", async () => {
    const options: unknown[] = [];
    const ctx = fakeContext({
      getMembers: async (_guild: string, o: unknown) => {
        options.push(o);
        return [];
      },
    });

    await callTool(memberTools, ctx, "list_guild_members", {
      limit: 100,
      after: "100000000000000005",
    });

    expect(options).toEqual([{ limit: 100, after: "100000000000000005" }]);
  });

  test("turns a 403 from Discord into a ToolError, not a raw API error", async () => {
    const ctx = fakeContext({
      getMembers: async () => {
        throw restError(403);
      },
    });

    const result = await callTool(memberTools, ctx, "list_guild_members", {
      limit: 100,
    });

    expect(result.isError).toBe(true);
    expect(String(result.data).startsWith("Discord API error")).toBe(false);
  });
});
