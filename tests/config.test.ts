import { describe, expect, test } from "bun:test";
import { ConfigError, loadConfig } from "../src/config.ts";

const base = { DISCORD_TOKEN: "token" };
const httpToken = "x".repeat(32);

describe("loadConfig", () => {
  test("defaults to stdio, loopback HTTP, and every tool", () => {
    const config = loadConfig(base);
    expect(config.transport).toBe("stdio");
    expect(config.http.host).toBe("127.0.0.1");
    expect(config.http.port).toBe(8085);
    expect(config.policy).toEqual({ allow: ["*"], deny: [] });
    expect(config.fileRoots).toEqual([]);
    expect(config.defaultGuildId).toBeUndefined();
  });

  test("requires the Discord token", () => {
    expect(() => loadConfig({})).toThrow(ConfigError);
  });

  test("treats an empty guild ID as unset and rejects a non-snowflake", () => {
    expect(
      loadConfig({ ...base, DISCORD_GUILD_ID: "" }).defaultGuildId,
    ).toBeUndefined();
    expect(() => loadConfig({ ...base, DISCORD_GUILD_ID: "abc" })).toThrow(
      ConfigError,
    );
    expect(
      loadConfig({ ...base, DISCORD_GUILD_ID: "123456789012345678" })
        .defaultGuildId,
    ).toBe("123456789012345678");
  });

  test("parses the allowlist and the denylist", () => {
    const config = loadConfig({
      ...base,
      DISCORD_MCP_ALLOW: "@read, send_message",
      DISCORD_MCP_DENY: "@destructive",
    });
    expect(config.policy).toEqual({
      allow: ["@read", "send_message"],
      deny: ["@destructive"],
    });
  });

  test("requires a bearer token for HTTP on a non-loopback host", () => {
    const http = {
      ...base,
      DISCORD_MCP_TRANSPORT: "http",
      DISCORD_MCP_HTTP_HOST: "0.0.0.0",
    };
    expect(() => loadConfig(http)).toThrow(ConfigError);
    expect(
      loadConfig({ ...http, DISCORD_MCP_HTTP_TOKEN: httpToken }).http.token,
    ).toBe(httpToken);
  });

  test("allows HTTP without a token on loopback", () => {
    const config = loadConfig({ ...base, DISCORD_MCP_TRANSPORT: "http" });
    expect(config.http.token).toBeUndefined();
  });

  test("rejects a short bearer token", () => {
    expect(() =>
      loadConfig({ ...base, DISCORD_MCP_HTTP_TOKEN: "short" }),
    ).toThrow(ConfigError);
  });

  test("rejects a relative file root", () => {
    expect(() =>
      loadConfig({ ...base, DISCORD_MCP_FILE_ROOTS: "uploads" }),
    ).toThrow(ConfigError);
    expect(
      loadConfig({ ...base, DISCORD_MCP_FILE_ROOTS: "/srv/uploads" }).fileRoots,
    ).toEqual(["/srv/uploads"]);
  });
});
