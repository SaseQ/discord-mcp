/**
 * Writes every log line to stderr, because in stdio mode stdout carries the MCP messages.
 */
export const log = {
  info: (...args: unknown[]) => console.error("[discord-mcp]", ...args),
  warn: (...args: unknown[]) => console.error("[discord-mcp] warn:", ...args),
  error: (...args: unknown[]) => console.error("[discord-mcp] error:", ...args),
};
