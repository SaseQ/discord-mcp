import { describe, expect, test } from "bun:test";
import { McpServer } from "@modelcontextprotocol/server";
import type { HttpConfig } from "../src/config.ts";
import { createHttpApp } from "../src/http.ts";

const token = "t".repeat(40);
const config: HttpConfig = {
  host: "127.0.0.1",
  port: 0,
  token,
  allowedHosts: ["localhost", "127.0.0.1"],
};
const app = createHttpApp(
  config,
  () => new McpServer({ name: "test", version: "0" }),
);

const initialize = {
  jsonrpc: "2.0",
  id: 1,
  method: "initialize",
  params: {
    protocolVersion: "2025-11-25",
    capabilities: {},
    clientInfo: { name: "test", version: "0" },
  },
};

const post = (headers: Record<string, string>) =>
  app.fetch(
    new Request("http://localhost/mcp", {
      method: "POST",
      headers: {
        host: "localhost:8085",
        "content-type": "application/json",
        accept: "application/json, text/event-stream",
        ...headers,
      },
      body: JSON.stringify(initialize),
    }),
  );

describe("createHttpApp", () => {
  test("answers the health check without a token", async () => {
    const response = await app.fetch(new Request("http://localhost/healthz"));
    expect(response.status).toBe(200);
  });

  test("rejects a request with no or a wrong bearer token", async () => {
    expect((await post({})).status).toBe(401);
    expect(
      (await post({ authorization: `Bearer ${"u".repeat(40)}` })).status,
    ).toBe(401);
    expect((await post({ authorization: "Bearer short" })).status).toBe(401);
  });

  test("serves MCP with the right bearer token", async () => {
    const response = await post({ authorization: `Bearer ${token}` });
    expect(response.status).toBe(200);
    expect(await response.text()).toContain('"serverInfo"');
  });

  test("rejects a foreign Host or Origin, against DNS rebinding", async () => {
    const auth = { authorization: `Bearer ${token}` };
    expect((await post({ ...auth, host: "evil.example" })).status).toBe(403);
    expect(
      (await post({ ...auth, origin: "https://evil.example" })).status,
    ).toBe(403);
  });

  test("answers 404 off the MCP path", async () => {
    const response = await app.fetch(new Request("http://localhost/other"));
    expect(response.status).toBe(404);
  });
});
