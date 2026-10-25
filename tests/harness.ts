import type { McpServer, StandardSchemaV1 } from "@modelcontextprotocol/server";
import { DiscordRESTError } from "oceanic.js";
import type { Context } from "../src/context.ts";
import type { FileLoader } from "../src/files.ts";
import type { Tool } from "../src/tool.ts";

/** The server that the tests use when a tool is called without a guild ID. */
export const DEFAULT_GUILD = "111111111111111111";

type Handler = (args: unknown) => Promise<{
  isError?: boolean;
  content: unknown[];
}>;

export interface CallResult {
  isError: boolean;
  /** The parsed JSON of a successful result, the text of a string result, or the error text of a failed one. */
  data: unknown;
}

/**
 * Registers the tools on a fake MCP server, then calls one of them with the given context.
 * It parses the arguments with the input schema of the tool first, as the SDK does, and returns a failed result when they are invalid.
 */
export const callTool = async (
  tools: readonly Tool[],
  ctx: Context,
  name: string,
  args: Record<string, unknown>,
): Promise<CallResult> => {
  const registered = new Map<
    string,
    { schema: StandardSchemaV1; handler: Handler }
  >();
  const server = {
    registerTool: (
      toolName: string,
      config: { inputSchema: StandardSchemaV1 },
      handler: Handler,
    ) => {
      registered.set(toolName, { schema: config.inputSchema, handler });
    },
  };
  for (const tool of tools) tool.register(server as unknown as McpServer, ctx);
  const tool = registered.get(name);
  if (!tool) throw new Error(`no tool named ${name}`);
  const parsed = await tool.schema["~standard"].validate(args);
  if (parsed.issues) {
    return {
      isError: true,
      data: parsed.issues.map((issue) => issue.message).join("; "),
    };
  }
  const result = await tool.handler(parsed.value);
  const first = result.content[0] as { text: string } | undefined;
  const text = first?.text ?? "";
  if (result.isError) return { isError: true, data: text };
  try {
    return { isError: false, data: JSON.parse(text) as unknown };
  } catch {
    return { isError: false, data: text };
  }
};

export interface FakeRest {
  guilds?: Record<string, unknown>;
  channels?: Record<string, unknown>;
  users?: Record<string, unknown>;
  webhooks?: Record<string, unknown>;
}

/** A context whose REST routes are the given fake functions, and which never touches the network. */
export const fakeContext = (
  rest: FakeRest,
  extras: { files?: Partial<FileLoader>; user?: { id: string } } = {},
): Context =>
  ({
    client: { rest, user: extras.user ?? { id: "999999999999999999" } },
    files: extras.files,
    config: { maxUploadBytes: 10 * 1024 * 1024 },
    guildId: (given?: string) => given || DEFAULT_GUILD,
  }) as unknown as Context;

/** A REST error with the given HTTP status and Discord error code, as Oceanic raises it. */
export const restError = (
  status: number,
  code = 0,
  method: "GET" | "POST" | "PATCH" | "PUT" | "DELETE" = "GET",
) => {
  const response = new Response(null, { status });
  // Oceanic reads the path of the request from the URL of the response.
  Object.defineProperty(response, "url", {
    value: "https://discord.com/api/v10/fake",
  });
  return new DiscordRESTError(response, { code, message: "fake" }, method);
};

/** Records each call of a fake method, so that a test can check what reached Discord. */
export const recorder = <T>(result: T | ((...args: unknown[]) => T)) => {
  const calls: unknown[][] = [];
  const fn = async (...args: unknown[]) => {
    calls.push(args);
    return typeof result === "function"
      ? (result as (...a: unknown[]) => T)(...args)
      : result;
  };
  return Object.assign(fn, { calls });
};
