/**
 * Prints each tool, and each part of a merged tool, with its group and access level, and whether DISCORD_MCP_ALLOW and DISCORD_MCP_DENY enable it.
 * It needs no Discord token.
 */
import { isEnabled, parseSelectors } from "../src/policy.ts";
import { allTools } from "../src/tools/index.ts";

const allow = parseSelectors(Bun.env["DISCORD_MCP_ALLOW"]);
const policy = {
  allow: allow.length > 0 ? allow : ["*"],
  deny: parseSelectors(Bun.env["DISCORD_MCP_DENY"]),
};

// A merged tool shows one row for each part, because the selectors enable each part.
const rows = allTools
  .flatMap((t) =>
    (t.parts?.list ?? [t]).map((p) => ({
      tool: p.partOf ? `${p.partOf} (${p.name})` : p.name,
      group: p.group,
      access: p.access,
      enabled: isEnabled(policy, p),
    })),
  )
  .sort(
    (a, b) => a.group.localeCompare(b.group) || a.tool.localeCompare(b.tool),
  );
console.table(rows);
console.log(
  `${rows.filter((r) => r.enabled).length} of ${rows.length} tools and parts enabled`,
);
