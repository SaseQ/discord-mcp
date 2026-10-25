import type { Context } from "../src/context.ts";
import { fakeContext as fakeRestContext } from "./harness.ts";

export { callTool, DEFAULT_GUILD, restError } from "./harness.ts";

/** A context whose guild routes are the given fake functions. */
export const fakeContext = (guilds: Record<string, unknown>): Context =>
  fakeRestContext({ guilds });

/** A guild member shaped like the Oceanic member that the REST routes return. */
export const fakeMember = (fields: {
  id: string;
  username: string;
  globalName?: string | null;
  nick?: string | null;
  discriminator?: string;
  bot?: boolean;
  roles?: string[];
  joinedAt?: Date | null;
  communicationDisabledUntil?: Date | null;
}) => ({
  user: {
    id: fields.id,
    username: fields.username,
    globalName: fields.globalName ?? null,
    discriminator: fields.discriminator ?? "0",
    bot: fields.bot ?? false,
  },
  nick: fields.nick ?? null,
  roles: fields.roles ?? [],
  joinedAt: fields.joinedAt ?? null,
  communicationDisabledUntil: fields.communicationDisabledUntil ?? null,
});

/** A role shaped like the Oceanic role that the REST routes return. */
export const fakeRole = (fields: {
  id: string;
  name: string;
  color?: number;
  permissions?: string;
}) => ({
  id: fields.id,
  name: fields.name,
  color: fields.color ?? 0,
  position: 1,
  hoist: false,
  mentionable: false,
  permissions: { allow: BigInt(fields.permissions ?? "0") },
});
