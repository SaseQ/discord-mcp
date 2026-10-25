import type {
  CallToolResult,
  McpServer,
  StandardSchemaWithJSON,
} from "@modelcontextprotocol/server";
import { DiscordRESTError } from "oceanic.js";
import { z } from "zod";
import type { Context } from "./context.ts";
import {
  ACCESS_LEVELS,
  type Access,
  type ToolGroup,
  type ToolTraits,
} from "./policy.ts";

export interface ToolSpec<S extends z.ZodObject> {
  name: string;
  title: string;
  description: string;
  group: ToolGroup;
  access: Access;
  input: S;
  /** Returns the result, which the server sends as JSON text, or a string that it sends as it is. */
  run: (args: z.output<S>, ctx: Context) => Promise<unknown>;
}

export interface Tool extends ToolTraits {
  register: (server: McpServer, ctx: Context) => void;
  /** The parts of a merged tool, which the allowlist selects one by one, and the same tool limited to some of them. */
  parts?: {
    list: readonly Part[];
    limit: (parts: readonly Part[]) => Tool;
  };
}

/** One part of a merged tool: the value of its discriminator that the old tool `name` covered. */
export interface Part extends ToolTraits {
  value: string;
  /** What the description says about this value. */
  note: string;
  /** The parameters that only some parts take. A call gives them only with a value whose part lists them. */
  params?: readonly string[];
  /** The params that a call of this part must give. The listed schema requires those that every enabled part needs. */
  required?: readonly string[];
}

export interface MergedSpec<S extends z.ZodObject>
  extends Omit<ToolSpec<S>, "access"> {
  /** The enum parameter that picks the part. With no key, a call is the part whose params it gives, or else the part with no params. */
  key?: string;
  parts: ReadonlyArray<Omit<Part, "partOf">>;
}

/**
 * Defines one tool that does the work of several, such as `moderate_member`.
 * The listed enum of `key`, the parameters, the description, and the access level hold only the parts that the allowlist enables.
 */
export const defineMergedTool = <S extends z.ZodObject>(
  spec: MergedSpec<S>,
): Tool => {
  const owned = new Set(spec.parts.flatMap((p) => p.params ?? []));
  const build = (parts: readonly Part[]): Tool => {
    const kept = new Set(parts.flatMap((p) => p.params ?? []));
    const hidden = [...owned].filter((param) => !kept.has(param));
    const values = parts.map((p) => p.value) as [string, ...string[]];
    // With no key and no part that takes none of them, a call must give the kept params.
    const required = spec.key
      ? [...kept].filter((param) =>
          parts.every((p) => p.required?.includes(param)),
        )
      : parts.every((p) => p.params?.length)
        ? [...kept]
        : [];
    const access =
      ACCESS_LEVELS[
        Math.max(...parts.map((p) => ACCESS_LEVELS.indexOf(p.access)))
      ] ?? "destructive";
    const tool = defineTool({
      ...spec,
      access,
      description: [spec.description, ...parts.map((p) => p.note)]
        .filter(Boolean)
        .join(" "),
      input: limitInput(spec.input, hidden, required, spec.key, values) as S,
      run: async (args, ctx) => {
        const given = args as Record<string, unknown>;
        // An empty list gives nothing, so `files: []` does not pick the upload part.
        const has = (param: string) => {
          const value = given[param];
          return value != null && !(Array.isArray(value) && value.length === 0);
        };
        const part = spec.key
          ? parts.find((p) => p.value === given[spec.key as string])
          : (parts.find((p) => p.params?.some(has)) ??
            parts.find((p) => !p.params?.length));
        if (!part) throw new ToolError(`Give ${[...kept].join(" or ")}`);
        for (const param of owned) {
          if (has(param) && !part.params?.includes(param))
            throw new ToolError(`${param} is not for ${part.value}`);
        }
        return spec.run(args, ctx);
      },
    });
    return { ...tool, parts: { list: parts, limit: build } };
  };
  return build(spec.parts.map((p) => ({ ...p, partOf: spec.name })));
};

/** The input of a merged tool with only the enabled parts: hidden parameters are refused, not dropped. */
const limitInput = (
  input: z.ZodObject,
  hidden: readonly string[],
  required: readonly string[],
  key: string | undefined,
  values: [string, ...string[]],
): z.ZodObject => {
  const mask = (params: readonly string[]) =>
    Object.fromEntries(params.map((param) => [param, true])) as never;
  const kept = input.omit(mask(hidden)).required(mask(required));
  const keyed = key ? kept.extend({ [key]: z.enum(values) }) : kept;
  return hidden.length > 0 ? keyed.strict() : keyed;
};

/** A failure that the caller can fix, such as a bad argument or a missing resource. */
export class ToolError extends Error {
  override name = "ToolError";
}

export const defineTool = <S extends z.ZodObject>(spec: ToolSpec<S>): Tool => ({
  name: spec.name,
  group: spec.group,
  access: spec.access,
  register: (server, ctx) => {
    server.registerTool(
      spec.name,
      {
        title: spec.title,
        description: spec.description,
        inputSchema: wireSchema(spec.input),
        annotations: {
          readOnlyHint: spec.access === "read",
          destructiveHint: spec.access === "destructive",
          openWorldHint: true,
        },
      },
      async (args): Promise<CallToolResult> => {
        try {
          return textResult(await spec.run(args as z.output<S>, ctx));
        } catch (error) {
          return errorResult(error);
        }
      },
    );
  },
});

/** Zod validates the arguments, and the listed JSON Schema leaves out `$schema`, which each tool would repeat. */
const wireSchema = (schema: z.ZodObject): StandardSchemaWithJSON => {
  const std = schema["~standard"];
  return {
    "~standard": {
      ...std,
      jsonSchema: {
        ...std.jsonSchema,
        input: (options) => {
          const { $schema: _, ...rest } = std.jsonSchema.input(options);
          return rest;
        },
      },
    },
  };
};

/** Compact JSON with no `null` fields, so that each result spends no tokens on whitespace or empty values. */
const textResult = (value: unknown): CallToolResult => ({
  content: [
    {
      type: "text",
      text:
        typeof value === "string"
          ? value
          : JSON.stringify(value, (_, v: unknown) => v ?? undefined),
    },
  ],
});

export const errorResult = (error: unknown): CallToolResult => ({
  isError: true,
  content: [{ type: "text", text: describeError(error) }],
});

export const describeError = (error: unknown): string => {
  if (error instanceof DiscordRESTError) {
    return `Discord API error ${error.status} on ${error.method} ${error.path}: ${error.message}`;
  }
  if (error instanceof Error) return error.message;
  return String(error);
};
