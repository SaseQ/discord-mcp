/**
 * Writes `llms-full.txt`: the README, then every tool with its parameters, from the tool definitions.
 * `bun run scripts/llms.ts --check` fails when the file is stale. It needs no Discord token.
 */
import type { McpServer } from "@modelcontextprotocol/server";
import type { Context } from "../src/context.ts";
import { TOOL_GROUPS } from "../src/policy.ts";
import { allTools } from "../src/tools/index.ts";

const ROOT = `${import.meta.dir}/..`;
export const LLMS_FULL = `${ROOT}/llms-full.txt`;

interface JsonSchema {
  type?: string;
  enum?: unknown[];
  default?: unknown;
  description?: string;
  minimum?: number;
  maximum?: number;
  maxLength?: number;
  anyOf?: JsonSchema[];
  items?: JsonSchema;
  properties?: Record<string, JsonSchema>;
  required?: string[];
}

interface Listed {
  description: string;
  schema: JsonSchema;
}

/** The description and the JSON Schema of each tool, as a client lists them. */
const listed = (): Map<string, Listed> => {
  const out = new Map<string, Listed>();
  const server = {
    registerTool: (
      name: string,
      config: {
        description: string;
        inputSchema: {
          "~standard": {
            jsonSchema: { input: (o: { target: string }) => JsonSchema };
          };
        };
      },
    ) => {
      out.set(name, {
        description: config.description,
        schema: config.inputSchema["~standard"].jsonSchema.input({
          target: "draft-2020-12",
        }),
      });
    },
  };
  for (const tool of allTools)
    tool.register(server as unknown as McpServer, {} as Context);
  return out;
};

const typeOf = (s: JsonSchema): string => {
  if (s.enum)
    return `one of ${s.enum.map((v) => `\`${String(v)}\``).join(", ")}`;
  if (s.anyOf) return [...new Set(s.anyOf.map(typeOf))].join(" or ");
  if (s.type === "array" && s.items) {
    const fields = Object.keys(s.items.properties ?? {});
    return fields.length > 0
      ? `array of objects with ${fields.map((f) => `\`${f}\``).join(", ")}`
      : `array of ${typeOf(s.items)}`;
  }
  return s.type ?? "any";
};

const param = (name: string, s: JsonSchema, required: boolean): string => {
  const bits = [typeOf(s)];
  if (s.minimum !== undefined || s.maximum !== undefined)
    bits.push(`${s.minimum ?? ""}..${s.maximum ?? ""}`);
  if (s.maxLength !== undefined) bits.push(`at most ${s.maxLength} characters`);
  if (s.default !== undefined)
    bits.push(`default ${JSON.stringify(s.default)}`);
  if (s.description) bits.push(s.description);
  return `- \`${name}\`${required ? " (required)" : ""}: ${bits.join("; ")}`;
};

export const renderLlmsFull = async (): Promise<string> => {
  const readme = await Bun.file(`${ROOT}/README.md`).text();
  // The logo and the title come before the first heading line.
  const body = readme.slice(
    readme.indexOf("\n", readme.indexOf("\n# ") + 1) + 1,
  );
  const tools = listed();
  const lines = [
    "# discord-mcp-next",
    "",
    "> The full reference of discord-mcp-next, an MCP server for Discord: the README, then every tool with its parameters.",
    "",
    body.trim(),
    "",
    "## Tool Reference",
    "",
    "Each tool lists its access level and parameters. A merged tool lists its parts: the old tool names, which `DISCORD_MCP_ALLOW` and `DISCORD_MCP_DENY` still select one by one.",
  ];
  for (const group of TOOL_GROUPS) {
    lines.push("", `### @${group}`);
    for (const tool of allTools.filter((t) => t.group === group)) {
      const { description, schema } = tools.get(tool.name) ?? {
        description: "",
        schema: {},
      };
      lines.push("", `#### \`${tool.name}\``, "", description, "");
      lines.push(`Access: \`${tool.access}\`.`);
      if (tool.parts) {
        const parts = tool.parts.list.map(
          (p) => `\`${p.name}\` (${p.value}; @${p.group}, ${p.access})`,
        );
        lines.push(`Parts: ${parts.join(", ")}.`);
      }
      const props = Object.entries(schema.properties ?? {});
      if (props.length > 0) {
        lines.push("");
        for (const [name, s] of props)
          lines.push(param(name, s, schema.required?.includes(name) ?? false));
      }
    }
    const elsewhere = allTools.flatMap((t) =>
      t.group === group
        ? []
        : (t.parts?.list ?? []).filter((p) => p.group === group),
    );
    if (elsewhere.length > 0) {
      const refs = elsewhere.map(
        (p) =>
          `\`${p.partOf}\` with \`${p.value}\` (\`${p.name}\`, ${p.access})`,
      );
      lines.push("", `Also in @${group}: ${refs.join(", ")}.`);
    }
  }
  return `${lines.join("\n")}\n`;
};

if (import.meta.main) {
  const text = await renderLlmsFull();
  if (Bun.argv.includes("--check")) {
    if ((await Bun.file(LLMS_FULL).text()) !== text) {
      console.error("llms-full.txt is stale. Run `bun run scripts/llms.ts`.");
      process.exit(1);
    }
  } else {
    await Bun.write(LLMS_FULL, text);
  }
}
