import { describe, expect, test } from "bun:test";
import { ConfigError } from "../src/config.ts";
import { createHttpApp } from "../src/http.ts";
import type { ToolPolicy } from "../src/policy.ts";
import { createServer, selectTools } from "../src/server.ts";
import { allTools } from "../src/tools/index.ts";
import { callTool, fakeContext, recorder } from "./harness.ts";
import { fakeMessage } from "./messages-harness.ts";

interface ListedTool {
  name: string;
  inputSchema: Record<string, unknown>;
  annotations: { readOnlyHint: boolean; destructiveHint: boolean };
}

const MODERN = "2026-07-28";

/** Sends one JSON-RPC request over the HTTP transport, as a client does, and returns its result. */
const rpc = async (
  policy: ToolPolicy,
  method: string,
  version = "2025-11-25",
): Promise<Record<string, unknown>> => {
  const tools = selectTools(allTools, policy);
  const app = createHttpApp(
    {
      host: "127.0.0.1",
      port: 0,
      token: undefined,
      allowedHosts: ["localhost"],
    },
    () => createServer(tools, fakeContext({})),
  );
  const modern = version >= MODERN;
  const response = await app.fetch(
    new Request("http://localhost/mcp", {
      method: "POST",
      headers: {
        host: "localhost",
        "content-type": "application/json",
        accept: "application/json, text/event-stream",
        "mcp-protocol-version": version,
        ...(modern ? { "mcp-method": method } : {}),
      },
      body: JSON.stringify({
        jsonrpc: "2.0",
        id: 1,
        method,
        params: modern
          ? {
              _meta: {
                "io.modelcontextprotocol/protocolVersion": version,
                "io.modelcontextprotocol/clientCapabilities": {},
                "io.modelcontextprotocol/clientInfo": {
                  name: "test",
                  version: "1",
                },
              },
            }
          : {},
      }),
    }),
  );
  const body = await response.text();
  const data =
    body
      .split("\n")
      .find((line) => line.startsWith("data: "))
      ?.slice(6) ?? body;
  const parsed = JSON.parse(data) as {
    result?: Record<string, unknown>;
    error?: unknown;
  };
  if (!parsed.result) throw new Error(`no result in ${body}`);
  return parsed.result;
};

/** Lists the tools over the MCP protocol, as a client sees them. */
const listTools = async (policy: ToolPolicy): Promise<ListedTool[]> =>
  (await rpc(policy, "tools/list"))["tools"] as ListedTool[];

describe("protocol", () => {
  const all = { allow: ["*"], deny: [] };

  test("server/discover offers the 2026-07-28 revision", async () => {
    const result = await rpc(all, "server/discover", MODERN);
    expect(result["supportedVersions"]).toContain(MODERN);
  });

  test("a 2026-07-28 tool list carries a cache lifetime", async () => {
    const result = await rpc(all, "tools/list", MODERN);
    expect(result["ttlMs"]).toBeGreaterThan(0);
    expect(result["cacheScope"]).toBeDefined();
  });

  test("the listed schemas omit $schema", async () => {
    const listed = await listTools(all);
    expect(listed.some((t) => "$schema" in t.inputSchema)).toBe(false);
  });
});

describe("tool registration", () => {
  test("tool names are unique", () => {
    const names = allTools.map((t) => t.name);
    expect(new Set(names).size).toBe(names.length);
  });

  test("every tool is listed when everything is allowed", async () => {
    const listed = await listTools({ allow: ["*"], deny: [] });
    expect(listed.map((t) => t.name).sort()).toEqual(
      allTools.map((t) => t.name).sort(),
    );
  });

  test("denied tools are not listed to the client", async () => {
    const listed = await listTools({ allow: ["*"], deny: ["@destructive"] });
    const names = listed.map((t) => t.name);
    for (const name of ["delete_channel", "delete_category", "delete_role"]) {
      expect(names).not.toContain(name);
    }
    expect(names).toContain("send_message");
    expect(listed.every((t) => !t.annotations.destructiveHint)).toBe(true);
  });

  test("a read-only allowlist lists only read-only tools", async () => {
    const listed = await listTools({ allow: ["@read"], deny: [] });
    expect(listed.length).toBeGreaterThan(0);
    expect(listed.every((t) => t.annotations.readOnlyHint)).toBe(true);
  });

  test("the annotations follow the access level", async () => {
    const listed = await listTools({ allow: ["*"], deny: [] });
    for (const tool of allTools) {
      const annotations = listed.find((t) => t.name === tool.name)?.annotations;
      expect(annotations?.readOnlyHint).toBe(tool.access === "read");
      expect(annotations?.destructiveHint).toBe(tool.access === "destructive");
    }
  });

  test("a selector that matches no tool stops the start", () => {
    expect(() =>
      selectTools(allTools, { allow: ["*"], deny: ["delete_chanel"] }),
    ).toThrow(ConfigError);
  });
});

/** The values that a listed tool accepts for one enum parameter, or undefined when the tool is not listed. */
const choices = async (policy: ToolPolicy, tool: string, key: string) => {
  const listed = (await listTools(policy)).find((t) => t.name === tool);
  const properties = listed?.inputSchema["properties"] as
    | Record<string, { enum?: string[] }>
    | undefined;
  return properties?.[key]?.enum;
};

describe("merged tools", () => {
  test("a deny of @destructive keeps the write actions of moderate_member, with no destructive hint", async () => {
    const policy = { allow: ["*"], deny: ["@destructive"] };
    expect(await choices(policy, "moderate_member", "action")).toEqual([
      "unban",
      "timeout",
      "move",
      "disconnect",
    ]);
    const listed = await listTools(policy);
    expect(
      listed.find((t) => t.name === "moderate_member")?.annotations,
    ).toMatchObject({ destructiveHint: false, readOnlyHint: false });
  });

  test("the old tool name of an action selects that action only", async () => {
    const policy = { allow: ["*"], deny: ["kick_member"] };
    expect(() => selectTools(allTools, policy)).not.toThrow();
    const actions = await choices(policy, "moderate_member", "action");
    expect(actions).not.toContain("kick");
    expect(actions).toContain("ban");
  });

  test("each part allows only its own value", async () => {
    const policy = { allow: ["create_voice_channel"], deny: [] };
    expect(await choices(policy, "create_channel", "type")).toEqual(["voice"]);
  });

  test("a limited tool hides the parameters of its disabled parts", async () => {
    const policy = { allow: ["*"], deny: ["ban_member", "move_member"] };
    const listed = (await listTools(policy)).find(
      (t) => t.name === "moderate_member",
    );
    const properties = Object.keys(
      listed?.inputSchema["properties"] as Record<string, unknown>,
    );
    expect(properties).toContain("durationSeconds");
    expect(properties).not.toContain("deleteMessageSeconds");
    expect(properties).not.toContain("channelId");
  });

  test("the name of a merged tool selects all of its actions", async () => {
    const policy = { allow: ["*"], deny: ["moderate_member"] };
    expect(await choices(policy, "moderate_member", "action")).toBeUndefined();
  });

  test("a group selects the actions of that group", async () => {
    const policy = { allow: ["@voice"], deny: [] };
    expect(await choices(policy, "moderate_member", "action")).toEqual([
      "move",
      "disconnect",
    ]);
    expect(await choices(policy, "create_channel", "type")).toEqual([
      "voice",
      "stage",
    ]);
  });

  test("a call with a denied action fails and reaches no Discord route", async () => {
    const removeMember = recorder(undefined);
    const tools = selectTools(allTools, {
      allow: ["*"],
      deny: ["@destructive"],
    });
    const result = await callTool(
      tools,
      fakeContext({ guilds: { removeMember } }),
      "moderate_member",
      { action: "kick", userId: "100000000000000001" },
    );
    expect(result.isError).toBe(true);
    expect(removeMember.calls).toHaveLength(0);
  });

  test("the old names of react, set_member_role, and upsert_channel_permissions select one value", async () => {
    expect(
      await choices(
        { allow: ["*"], deny: ["remove_reaction"] },
        "react",
        "action",
      ),
    ).toEqual(["add"]);
    expect(
      await choices(
        { allow: ["assign_role"], deny: [] },
        "set_member_role",
        "action",
      ),
    ).toEqual(["add"]);
    expect(
      await choices(
        { allow: ["upsert_member_channel_permissions"], deny: [] },
        "upsert_channel_permissions",
        "targetType",
      ),
    ).toEqual(["member"]);
  });

  test("a parameter of a disabled part is refused, not dropped", async () => {
    const removeMember = recorder(undefined);
    const tools = selectTools(allTools, {
      allow: ["*"],
      deny: ["timeout_member"],
    });
    const getMember = async () => ({ user: { username: "someone" } });
    const result = await callTool(
      tools,
      fakeContext({ guilds: { removeMember, getMember } }),
      "moderate_member",
      { action: "kick", userId: "100000000000000001", durationSeconds: 60 },
    );
    expect(result.isError).toBe(true);
    expect(removeMember.calls).toHaveLength(0);
  });

  const channel = { id: "200000000000000001", type: 0 };
  const sent = fakeMessage({ channelID: channel.id });
  const upload = [{ url: "https://example.com/a.png" }];
  const sendContext = (createMessage: ReturnType<typeof recorder>) =>
    fakeContext(
      {
        channels: {
          get: async () => channel,
          createDM: async () => channel,
          createMessage,
        },
      },
      {
        files: {
          load: async () => ({ name: "a.png", contents: Buffer.from("x") }),
        },
      },
    );

  for (const [tool, filePart, target] of [
    ["send_message", "send_file", { channelId: channel.id }],
    [
      "send_private_message",
      "send_private_file",
      { userId: "100000000000000001" },
    ],
  ] as const) {
    test(`a deny of ${filePart} removes files from ${tool} and refuses an upload`, async () => {
      const policy = { allow: ["*"], deny: [filePart] };
      const listed = (await listTools(policy)).find((t) => t.name === tool);
      expect(
        Object.keys(listed?.inputSchema["properties"] ?? {}),
      ).not.toContain("files");
      const createMessage = recorder(sent);
      const result = await callTool(
        selectTools(allTools, policy),
        sendContext(createMessage),
        tool,
        { ...target, message: "hi", files: upload },
      );
      expect(result.isError).toBe(true);
      expect(createMessage.calls).toHaveLength(0);
    });

    test(`an allow of ${filePart} alone lists ${tool} for uploads only`, async () => {
      const policy = { allow: [filePart], deny: [] };
      const tools = selectTools(allTools, policy);
      expect(tools.map((t) => t.name)).toEqual([tool]);
      const listed = (await listTools(policy)).find((t) => t.name === tool);
      expect(listed?.inputSchema["required"]).toContain("files");
      const createMessage = recorder(sent);
      for (const args of [{ message: "hi" }, { message: "hi", files: [] }]) {
        const result = await callTool(tools, sendContext(createMessage), tool, {
          ...target,
          ...args,
        });
        expect(result.isError).toBe(true);
      }
      expect(createMessage.calls).toHaveLength(0);
      const uploaded = await callTool(tools, sendContext(createMessage), tool, {
        ...target,
        files: upload,
      });
      expect(uploaded.isError).toBeFalsy();
      expect(createMessage.calls).toHaveLength(1);
    });

    test(`a deny of ${filePart} still sends text with ${tool}`, async () => {
      const createMessage = recorder(sent);
      const result = await callTool(
        selectTools(allTools, { allow: ["*"], deny: [filePart] }),
        sendContext(createMessage),
        tool,
        { ...target, message: "hi" },
      );
      expect(result.isError).toBeFalsy();
      expect(createMessage.calls).toHaveLength(1);
    });
  }
});
