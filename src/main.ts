#!/usr/bin/env bun
import { serveStdio } from "@modelcontextprotocol/server/stdio";
import { Client, DiscordRESTError } from "oceanic.js";
import { ConfigError, loadConfig } from "./config.ts";
import { createContext } from "./context.ts";
import { createFileLoader } from "./files.ts";
import { serveHttp } from "./http.ts";
import { log } from "./log.ts";
import { createServer, selectTools } from "./server.ts";
import { describeError } from "./tool.ts";
import { allTools } from "./tools/index.ts";

const main = async () => {
  const config = loadConfig(Bun.env);
  const tools = selectTools(allTools, config.policy);
  log.info(`${tools.length} of ${allTools.length} tools enabled`);

  // REST only: no gateway connection, so no intent is needed except Server Members for member lists.
  const client = new Client({
    auth: `Bot ${config.discordToken}`,
    gateway: { intents: 0 },
  });
  client.on("warn", (message) => log.warn(message));
  client.on("error", (error) => log.error(error));
  await client.restMode(false);
  log.info(`Logged in to Discord as ${client.user.tag}`);

  const files = createFileLoader({
    roots: config.fileRoots,
    maxBytes: config.maxUploadBytes,
  });
  const ctx = createContext(client, config, files);

  let stop: () => Promise<void>;
  if (config.transport === "http") {
    if (
      !Bun.env["DISCORD_MCP_HTTP_ALLOWED_HOSTS"] &&
      !["127.0.0.1", "::1", "localhost"].includes(config.http.host)
    ) {
      log.warn(
        "DISCORD_MCP_HTTP_ALLOWED_HOSTS is not set, so only requests to localhost are answered. Add the host name that clients use.",
      );
    }
    const http = serveHttp(config.http, () => createServer(tools, ctx));
    stop = http.stop;
  } else {
    // serveStdio answers both the 2026-07-28 stateless protocol and the legacy handshake.
    const stdio = serveStdio(() => createServer(tools, ctx));
    stop = () => stdio.close();
  }

  const shutdown = async () => {
    await stop();
    client.disconnect(false);
    process.exit(0);
  };
  process.on("SIGINT", shutdown);
  process.on("SIGTERM", shutdown);
};

main().catch((error: unknown) => {
  if (error instanceof DiscordRESTError && error.status === 401) {
    log.error(
      "Discord rejected DISCORD_TOKEN. Use the bot token from Developer Portal > Bot > Reset Token, not the client ID or secret.",
    );
  } else if (
    error instanceof ConfigError ||
    error instanceof DiscordRESTError
  ) {
    log.error(
      error instanceof ConfigError ? error.message : describeError(error),
    );
  } else {
    log.error(error);
  }
  process.exit(1);
});
