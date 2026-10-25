import { timingSafeEqual } from "node:crypto";
import {
  createMcpHandler,
  hostHeaderValidationResponse,
  type McpServer,
  originValidationResponse,
} from "@modelcontextprotocol/server";
import type { HttpConfig } from "./config.ts";
import { log } from "./log.ts";

export const MCP_PATH = "/mcp";
export const HEALTH_PATH = "/healthz";

/** Returns the fetch handler of the HTTP transport: health check, DNS rebinding guard, bearer token, then MCP. */
export const createHttpApp = (
  config: HttpConfig,
  newServer: () => McpServer,
) => {
  const mcp = createMcpHandler(newServer, {
    onerror: (error) => log.error("MCP HTTP error:", error.message),
  });
  const expected =
    config.token === undefined ? undefined : Buffer.from(config.token);

  const authorized = (request: Request): boolean => {
    if (expected === undefined) return true;
    const header = request.headers.get("authorization") ?? "";
    const match = /^Bearer (.+)$/i.exec(header);
    if (!match?.[1]) return false;
    const given = Buffer.from(match[1]);
    return given.length === expected.length && timingSafeEqual(given, expected);
  };

  const fetch = async (request: Request): Promise<Response> => {
    const { pathname } = new URL(request.url);
    if (pathname === HEALTH_PATH && request.method === "GET") {
      return Response.json({ status: "ok" });
    }
    if (pathname !== MCP_PATH)
      return new Response("Not found", { status: 404 });

    const rejected =
      hostHeaderValidationResponse(request, config.allowedHosts) ??
      originValidationResponse(request, config.allowedHosts);
    if (rejected) return rejected;
    if (!authorized(request)) {
      return new Response("Unauthorized", {
        status: 401,
        headers: { "www-authenticate": 'Bearer realm="discord-mcp"' },
      });
    }
    return mcp.fetch(request);
  };

  return { fetch, close: () => mcp.close() };
};

export const serveHttp = (config: HttpConfig, newServer: () => McpServer) => {
  const app = createHttpApp(config, newServer);
  const server = Bun.serve({
    hostname: config.host,
    port: config.port,
    fetch: app.fetch,
  });
  log.info(`MCP over HTTP on http://${config.host}:${server.port}${MCP_PATH}`);
  return {
    stop: async () => {
      await server.stop();
      await app.close();
    },
  };
};
