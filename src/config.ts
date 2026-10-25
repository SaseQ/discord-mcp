import { isAbsolute, resolve } from "node:path";
import { z } from "zod";
import { parseSelectors, type ToolPolicy } from "./policy.ts";

const LOOPBACK_HOSTS = new Set(["127.0.0.1", "::1", "localhost"]);

const snowflake = z.string().regex(/^\d{17,20}$/, "must be a Discord ID");

const envSchema = z.object({
  DISCORD_TOKEN: z
    .string({ error: "is required: the bot token from Developer Portal > Bot" })
    .min(1, "is required: the bot token from Developer Portal > Bot"),
  DISCORD_GUILD_ID: z
    .string()
    .optional()
    .transform((v) => (v ? v : undefined))
    .pipe(snowflake.optional()),
  DISCORD_MCP_TRANSPORT: z.enum(["stdio", "http"]).default("stdio"),
  DISCORD_MCP_HTTP_HOST: z.string().min(1).default("127.0.0.1"),
  DISCORD_MCP_HTTP_PORT: z.coerce
    .number()
    .int()
    .min(0)
    .max(65535)
    .default(8085),
  DISCORD_MCP_HTTP_TOKEN: z.string().min(32).optional(),
  DISCORD_MCP_HTTP_ALLOWED_HOSTS: z.string().optional(),
  DISCORD_MCP_ALLOW: z.string().optional(),
  DISCORD_MCP_DENY: z.string().optional(),
  DISCORD_MCP_FILE_ROOTS: z.string().optional(),
  DISCORD_MCP_MAX_UPLOAD_BYTES: z.coerce
    .number()
    .int()
    .positive()
    .default(10 * 1024 * 1024),
});

export interface HttpConfig {
  host: string;
  port: number;
  /** The bearer token that each request must send, or `undefined` on a loopback bind. */
  token: string | undefined;
  /** The `Host` header names that the server answers, against DNS rebinding. */
  allowedHosts: string[];
}

export interface Config {
  discordToken: string;
  defaultGuildId: string | undefined;
  transport: "stdio" | "http";
  http: HttpConfig;
  policy: ToolPolicy;
  /** The folders that a tool may read a local file from. Empty turns off local file reads. */
  fileRoots: string[];
  maxUploadBytes: number;
}

export class ConfigError extends Error {
  override name = "ConfigError";
}

export const loadConfig = (env: Record<string, string | undefined>): Config => {
  const parsed = envSchema.safeParse(env);
  if (!parsed.success) {
    const lines = parsed.error.issues.map(
      (i) => `${i.path.join(".") || "env"}: ${i.message}`,
    );
    throw new ConfigError(`Invalid configuration:\n${lines.join("\n")}`);
  }
  const e = parsed.data;

  const isLoopback = LOOPBACK_HOSTS.has(e.DISCORD_MCP_HTTP_HOST);
  if (
    e.DISCORD_MCP_TRANSPORT === "http" &&
    !isLoopback &&
    !e.DISCORD_MCP_HTTP_TOKEN
  ) {
    throw new ConfigError(
      `DISCORD_MCP_HTTP_TOKEN is required when DISCORD_MCP_HTTP_HOST (${e.DISCORD_MCP_HTTP_HOST}) is not a loopback address`,
    );
  }

  const allow = parseSelectors(e.DISCORD_MCP_ALLOW);
  const fileRoots = parseSelectors(e.DISCORD_MCP_FILE_ROOTS);
  for (const root of fileRoots) {
    if (!isAbsolute(root)) {
      throw new ConfigError(
        `DISCORD_MCP_FILE_ROOTS: ${root} is not an absolute path`,
      );
    }
  }

  return {
    discordToken: e.DISCORD_TOKEN,
    defaultGuildId: e.DISCORD_GUILD_ID,
    transport: e.DISCORD_MCP_TRANSPORT,
    http: {
      host: e.DISCORD_MCP_HTTP_HOST,
      port: e.DISCORD_MCP_HTTP_PORT,
      token: e.DISCORD_MCP_HTTP_TOKEN,
      allowedHosts: e.DISCORD_MCP_HTTP_ALLOWED_HOSTS
        ? parseSelectors(e.DISCORD_MCP_HTTP_ALLOWED_HOSTS)
        : ["localhost", "127.0.0.1", "[::1]"],
    },
    policy: {
      allow: allow.length > 0 ? allow : ["*"],
      deny: parseSelectors(e.DISCORD_MCP_DENY),
    },
    fileRoots: fileRoots.map((root) => resolve(root)),
    maxUploadBytes: e.DISCORD_MCP_MAX_UPLOAD_BYTES,
  };
};
