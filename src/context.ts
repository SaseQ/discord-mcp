import type { Client } from "oceanic.js";
import type { Config } from "./config.ts";
import type { FileLoader } from "./files.ts";
import { ToolError } from "./tool.ts";

export interface Context {
  client: Client;
  config: Config;
  files: FileLoader;
  /** Returns the given guild ID, or `DISCORD_GUILD_ID` when none is given. */
  guildId: (given?: string) => string;
}

export const createContext = (
  client: Client,
  config: Config,
  files: FileLoader,
): Context => ({
  client,
  config,
  files,
  guildId: (given) => {
    const id = given || config.defaultGuildId;
    if (!id)
      throw new ToolError(
        "guildId is required, because DISCORD_GUILD_ID is not set",
      );
    return id;
  },
});
