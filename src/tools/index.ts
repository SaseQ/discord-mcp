import type { Tool } from "../tool.ts";
import { categoryTools } from "./categories.ts";
import { channelTools } from "./channels.ts";
import { dmTools } from "./dms.ts";
import { emojiTools } from "./emojis.ts";
import { eventTools } from "./events.ts";
import { forumTools } from "./forums.ts";
import { inviteTools } from "./invites.ts";
import { memberTools } from "./members.ts";
import { messageTools } from "./messages.ts";
import { moderationTools } from "./moderation.ts";
import { permissionTools } from "./permissions.ts";
import { roleTools } from "./roles.ts";
import { serverTools } from "./server.ts";
import { threadTools } from "./threads.ts";
import { voiceTools } from "./voice.ts";
import { webhookTools } from "./webhooks.ts";

/** Every tool of the server, before the allowlist and the denylist apply. */
export const allTools: readonly Tool[] = [
  ...serverTools,
  ...messageTools,
  ...threadTools,
  ...dmTools,
  ...memberTools,
  ...moderationTools,
  ...roleTools,
  ...channelTools,
  ...categoryTools,
  ...permissionTools,
  ...voiceTools,
  ...forumTools,
  ...webhookTools,
  ...inviteTools,
  ...emojiTools,
  ...eventTools,
];
