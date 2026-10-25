import { ChannelTypes, DiscordRESTError } from "oceanic.js";

/** Drops the keys whose value is `undefined`, for option objects under `exactOptionalPropertyTypes`. */
export const definedOnly = <T extends Record<string, unknown>>(
  value: T,
): { [K in keyof T]?: Exclude<T[K], undefined> } =>
  Object.fromEntries(
    Object.entries(value).filter(([, v]) => v !== undefined),
  ) as {
    [K in keyof T]?: Exclude<T[K], undefined>;
  };

/** The `reason` option of a Discord mutation, which Discord writes to the audit log. */
export const reasonOption = (reason: string | undefined) =>
  reason === undefined ? {} : { reason };

/** True when the error is a Discord API error with the given HTTP status. */
export const hasStatus = (error: unknown, status: number) =>
  error instanceof DiscordRESTError && error.status === status;

const CHANNEL_TYPE_NAMES: Partial<Record<ChannelTypes, string>> = {
  [ChannelTypes.GUILD_TEXT]: "text",
  [ChannelTypes.DM]: "dm",
  [ChannelTypes.GUILD_VOICE]: "voice",
  [ChannelTypes.GROUP_DM]: "group_dm",
  [ChannelTypes.GUILD_CATEGORY]: "category",
  [ChannelTypes.GUILD_ANNOUNCEMENT]: "announcement",
  [ChannelTypes.ANNOUNCEMENT_THREAD]: "announcement_thread",
  [ChannelTypes.PUBLIC_THREAD]: "public_thread",
  [ChannelTypes.PRIVATE_THREAD]: "private_thread",
  [ChannelTypes.GUILD_STAGE_VOICE]: "stage",
  [ChannelTypes.GUILD_DIRECTORY]: "directory",
  [ChannelTypes.GUILD_FORUM]: "forum",
  [ChannelTypes.GUILD_MEDIA]: "media",
};

/** The readable name of a channel type, such as `text` or `voice`. */
export const channelTypeName = (type: ChannelTypes): string =>
  CHANNEL_TYPE_NAMES[type] ?? `unknown_${type}`;
