import { z } from "zod";

const SNOWFLAKE = /^\d{17,20}$/;

// A refinement validates without adding a pattern to each listed schema; the server instructions state the ID format.
export const snowflake = (what: string) =>
  z.string().refine((v) => SNOWFLAKE.test(v), `${what} must be a Discord ID`);

/** The guild of a tool, which falls back to `DISCORD_GUILD_ID`, as the server instructions say. */
export const guildId = snowflake("Server ID").optional();

/** The reason that Discord writes to the audit log. */
export const auditReason = z.string().max(512).optional();
