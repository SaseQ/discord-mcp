import {
  type AllowedMentions,
  type Attachment,
  ChannelTypes,
  type CreateMessageOptions,
  type EmbedField,
  type EmbedOptions,
  type GetChannelMessagesOptions,
  type Message,
  MessageTypes,
} from "oceanic.js";
import { z } from "zod";
import type { Context } from "../context.ts";
import type { FileSource, LoadedFile } from "../files.ts";
import { ToolError } from "../tool.ts";
import { definedOnly } from "./util.ts";

export { definedOnly };

/** The longest text that Discord accepts in one message. */
export const MAX_CONTENT_LENGTH = 2000;

/** The most messages that one read or search returns. */
export const MAX_READ_LIMIT = 100;

/** Channels that message tools may post to or read, as the Java server allowed. */
const MESSAGE_CHANNEL_TYPES: readonly number[] = [
  ChannelTypes.GUILD_TEXT,
  ChannelTypes.GUILD_ANNOUNCEMENT,
  ChannelTypes.PUBLIC_THREAD,
  ChannelTypes.PRIVATE_THREAD,
  ChannelTypes.ANNOUNCEMENT_THREAD,
];

/** Fetches a channel and checks that it takes messages. */
export const requireMessageChannel = async (
  ctx: Context,
  channelId: string,
) => {
  const channel = await ctx.client.rest.channels.get(channelId);
  if (!MESSAGE_CHANNEL_TYPES.includes(channel.type)) {
    throw new ToolError(`Channel ${channelId} is not a text channel or thread`);
  }
  return channel;
};

export const jumpUrl = (
  guildId: string | null,
  channelId: string,
  messageId: string,
): string =>
  `https://discord.com/channels/${guildId ?? "@me"}/${channelId}/${messageId}`;

/** Pings for `@everyone`, `@here`, and roles stay off unless `allowEveryone` is true. Users stay mentionable. */
export const mentionPolicy = (allowEveryone: boolean): AllowedMentions => ({
  everyone: allowEveryone,
  roles: allowEveryone,
  users: true,
  repliedUser: true,
});

export const fileFields = {
  filePath: z
    .string()
    .min(1)
    .optional()
    .describe("Absolute path under DISCORD_MCP_FILE_ROOTS"),
  fileUrl: z.string().min(1).optional().describe("Public https URL"),
  fileData: z.string().min(1).optional().describe("Base64 or data URI"),
  fileName: z
    .string()
    .min(1)
    .max(255)
    .optional()
    .describe("Required with fileData"),
};

/** One file source. Give exactly one of filePath, fileUrl, or fileData. */
export const fileSource = z
  .object(fileFields)
  .describe("One of filePath, fileUrl, or fileData");

const isoTime = z.iso.datetime({ offset: true });

// Flat strings for footer, image, thumbnail, and author keep the listed schema two levels shallower than Discord's objects.
export const embedSchema = z.object({
  title: z.string().min(1).max(256).optional(),
  description: z.string().min(1).max(4096).optional(),
  url: z.url().optional(),
  color: z
    .number()
    .int()
    .min(0)
    .max(0xffffff)
    .optional()
    .describe("RGB integer, such as 5793266"),
  fields: z
    .array(
      z.object({
        name: z.string().min(1).max(256),
        value: z.string().min(1).max(1024),
        inline: z.boolean().optional(),
      }),
    )
    .max(25)
    .optional(),
  footer: z.string().min(1).max(2048).optional(),
  image: z
    .string()
    .min(1)
    .optional()
    .describe("Image URL, or attachment://<fileName> of an attached file"),
  thumbnail: z.string().min(1).optional().describe("Small image URL, as image"),
  author: z.string().min(1).max(256).optional(),
  authorUrl: z.url().optional(),
  timestamp: z
    .string()
    .refine((v) => isoTime.safeParse(v).success, "must be an ISO 8601 time")
    .optional(),
});

export type EmbedInput = z.output<typeof embedSchema>;

export const toEmbedOptions = (embed: EmbedInput): EmbedOptions =>
  definedOnly({
    title: embed.title,
    description: embed.description,
    url: embed.url,
    color: embed.color,
    timestamp: embed.timestamp,
    fields: embed.fields?.map(
      (field): EmbedField => definedOnly({ ...field }) as EmbedField,
    ),
    footer: embed.footer === undefined ? undefined : { text: embed.footer },
    image: embed.image === undefined ? undefined : { url: embed.image },
    thumbnail:
      embed.thumbnail === undefined ? undefined : { url: embed.thumbnail },
    author:
      embed.author === undefined
        ? undefined
        : definedOnly({ name: embed.author, url: embed.authorUrl }),
  }) as EmbedOptions;

export interface MessageFields {
  content?: string | undefined;
  embeds?: EmbedInput[] | undefined;
  files?: LoadedFile[] | undefined;
  allowEveryone?: boolean | undefined;
}

/** Builds the content, embeds, files, and mention policy of a message. */
export const messageFields = (fields: MessageFields): CreateMessageOptions => {
  if (!fields.content && !fields.embeds?.length && !fields.files?.length) {
    throw new ToolError("Give message text, embeds, or files");
  }
  return definedOnly({
    content: fields.content,
    embeds: fields.embeds?.map(toEmbedOptions),
    files: fields.files,
    allowedMentions: mentionPolicy(fields.allowEveryone ?? false),
  });
};

/** Loads each file source in order, through the file loader that checks roots, hosts, and sizes. */
export const loadFiles = async (
  ctx: Context,
  sources: readonly FileSource[] | undefined,
): Promise<LoadedFile[]> => {
  // The files of one message share one budget, so many files cannot add up past the limit.
  const files: LoadedFile[] = [];
  let remaining = ctx.config.maxUploadBytes;
  for (const source of sources ?? []) {
    if (remaining <= 0) {
      throw new ToolError(
        `The files are over the total limit of ${ctx.config.maxUploadBytes} bytes`,
      );
    }
    const file = await ctx.files.load(source, remaining);
    remaining -= file.contents.length;
    files.push(file);
  }
  return files;
};

/** Maps `before`, `after`, or `around` to Oceanic's options, and rejects more than one cursor. */
export const historyOptions = (args: {
  count?: number | undefined;
  before?: string | undefined;
  after?: string | undefined;
  around?: string | undefined;
}): GetChannelMessagesOptions => {
  const cursors = [args.before, args.after, args.around].filter(
    (v) => v !== undefined,
  );
  if (cursors.length > 1) {
    throw new ToolError(
      "before, after, and around are mutually exclusive; give only one",
    );
  }
  return definedOnly({
    limit: args.count ?? MAX_READ_LIMIT,
    before: args.before,
    after: args.after,
    around: args.around,
  });
};

export interface AttachmentItem {
  id: string;
  filename: string;
  size: number;
  contentType: string | null;
  url: string;
}

export const toAttachmentItem = (attachment: Attachment): AttachmentItem => ({
  id: attachment.id,
  filename: attachment.filename,
  size: attachment.size,
  contentType: attachment.contentType ?? null,
  url: attachment.url,
});

export interface MessageItem {
  id: string;
  channelId: string;
  content: string;
  timestamp: string;
  editedTimestamp: string | null;
  author: {
    id: string;
    username: string;
    /** The server nickname or global name, when it differs from the username. */
    displayName?: string;
    bot?: true;
  };
  attachments?: AttachmentItem[];
  embedCount?: number;
  replyTo: string | null;
}

/** The shape of a message in every list of messages. Empty fields are left out, and so is the link, which the IDs make. */
export const toMessageItem = (message: Message): MessageItem => {
  const author: MessageItem["author"] = {
    id: message.author.id,
    username: message.author.username,
  };
  const shown = message.member?.nick ?? message.author.globalName;
  if (shown && shown !== message.author.username) author.displayName = shown;
  if (message.author.bot) author.bot = true;
  const attachments = message.attachments.toArray().map(toAttachmentItem);
  return {
    id: message.id,
    channelId: message.channelID,
    content: message.content,
    timestamp: message.timestamp.toISOString(),
    editedTimestamp: message.editedTimestamp?.toISOString() ?? null,
    author,
    ...(attachments.length > 0 ? { attachments } : {}),
    ...(message.embeds.length > 0 ? { embedCount: message.embeds.length } : {}),
    replyTo: repliedTo(message),
  };
};

/** The ID of the message that this one replies to. Forwards and crossposts also carry a reference, but are no reply. */
export const repliedTo = (message: Message): string | null =>
  message.type === MessageTypes.REPLY
    ? (message.messageReference?.messageID ?? null)
    : null;

/** One message with its link, for the tools that return a single message. */
export const toLinkedMessage = (message: Message) => ({
  ...toMessageItem(message),
  jumpUrl: jumpUrl(message.guildID ?? null, message.channelID, message.id),
});
