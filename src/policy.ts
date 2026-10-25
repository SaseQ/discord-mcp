/**
 * Decides which tools the server registers.
 *
 * A selector is one of:
 * - a tool name, such as `send_message`;
 * - a glob over tool names, such as `delete_*` or `*_webhook*`;
 * - `@read`, `@write`, or `@destructive`, the access level of a tool;
 * - `@<group>`, such as `@messages` or `@moderation`;
 * - `*`, every tool.
 *
 * A merged tool, such as `moderate_member`, has parts with their own name, group, and access level, and the selectors pick each part.
 *
 * A tool is enabled when a selector of the allowlist matches it and no selector of the denylist matches it.
 */

export const ACCESS_LEVELS = ["read", "write", "destructive"] as const;
export type Access = (typeof ACCESS_LEVELS)[number];

export const TOOL_GROUPS = [
  "server",
  "dms",
  "members",
  "messages",
  "channels",
  "categories",
  "permissions",
  "roles",
  "moderation",
  "voice",
  "threads",
  "forums",
  "webhooks",
  "invites",
  "emojis",
  "events",
] as const;
export type ToolGroup = (typeof TOOL_GROUPS)[number];

export interface ToolTraits {
  name: string;
  group: ToolGroup;
  access: Access;
  /** For one part of a merged tool, the name of that tool, which selects the part too. */
  partOf?: string;
}

export interface ToolPolicy {
  allow: readonly string[];
  deny: readonly string[];
}

export const ALLOW_ALL: ToolPolicy = { allow: ["*"], deny: [] };

export const parseSelectors = (raw: string | undefined): string[] => {
  if (raw === undefined) return [];
  return raw
    .split(/[,\s]+/)
    .map((s) => s.trim())
    .filter((s) => s.length > 0);
};

/** Returns the selectors that can never match a tool, so that a typo in a config fails at start and does not silently allow a tool. */
export const unknownSelectors = (
  selectors: readonly string[],
  tools: readonly ToolTraits[],
): string[] => {
  return selectors.filter((s) => !tools.some((t) => selectorMatches(s, t)));
};

export const selectorMatches = (
  selector: string,
  tool: ToolTraits,
): boolean => {
  if (selector === "*") return true;
  if (selector.startsWith("@")) {
    const tag = selector.slice(1);
    return tag === tool.access || tag === tool.group;
  }
  const names = tool.partOf ? [tool.name, tool.partOf] : [tool.name];
  if (selector.includes("*")) {
    const glob = globToRegExp(selector);
    return names.some((name) => glob.test(name));
  }
  return names.includes(selector);
};

export const isEnabled = (policy: ToolPolicy, tool: ToolTraits): boolean => {
  const allowed = policy.allow.some((s) => selectorMatches(s, tool));
  const denied = policy.deny.some((s) => selectorMatches(s, tool));
  return allowed && !denied;
};

const globToRegExp = (glob: string): RegExp => {
  const body = glob
    .split("*")
    .map((part) => part.replace(/[.+?^${}()|[\]\\]/g, "\\$&"))
    .join(".*");
  return new RegExp(`^${body}$`);
};
