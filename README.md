<div align="center">
  <img src="assets/img/Discord_MCP_full_logo.svg" width="60%" alt="DeepSeek-V3" />
</div>
<hr>
<div align="center" style="line-height: 1;">
    <a href="https://github.com/modelcontextprotocol/servers" target="_blank" style="margin: 2px;">
        <img alt="MCP Server" src="https://badge.mcpx.dev?type=server" style="display: inline-block; vertical-align: middle;"/>
    </a>
    <a href="https://discord.gg/5Uvxe5jteM" target="_blank" style="margin: 2px;">
        <img alt="Discord" src="https://img.shields.io/discord/936242526120194108?color=7389D8&label&logo=discord&logoColor=ffffff" style="display: inline-block; vertical-align: middle;"/>
    </a>
    <a href="https://github.com/SaseQ/discord-mcp/blob/main/LICENSE" target="_blank" style="margin: 2px;">
        <img alt="MIT License" src="https://img.shields.io/github/license/SaseQ/discord-mcp" style="display: inline-block; vertical-align: middle;"/>
    </a>
</div>


## 📖 Description

A [Model Context Protocol (MCP)](https://modelcontextprotocol.io/introduction) server for the Discord API using [(JDA)](https://jda.wiki/),
designed to integrate Discord bots with MCP-compatible applications such as Claude, ChatGPT etc. It allows AI assistants to interact with 
Discord by managing channels, sending messages, and retrieving server information. Ideal for building powerful Discord automation and AI-driven workflows.


## 🔬 Installation

### ► 🐳 Docker Installation (Recommended)

> [!NOTE]
> Docker installation is required. Full instructions can be found on [docker.com](https://www.docker.com/products/docker-desktop/).

#### 1) Set local env variables
```bash
export DISCORD_TOKEN="YOUR_DISCORD_BOT_TOKEN"
export DISCORD_GUILD_ID="OPTIONAL_DEFAULT_SERVER_ID"
export SPRING_PROFILES_ACTIVE=http
```

> [!IMPORTANT]
> Instructions for creating a Discord bot and retrieving its token can be found [here](https://discordjs.guide/legacy/preparations/app-setup).

> [!TIP]
> The `DISCORD_GUILD_ID` env variable is optional.
> 
> When provided, it sets a default Discord server ID so any tool that accepts a `guildId` parameter can omit it.

#### 2) Run the Docker container
```bash
docker run -d -i \
  --name discord-mcp \
  --restart unless-stopped \
  -p 8085:8085 \
  -e SPRING_PROFILES_ACTIVE \
  -e DISCORD_TOKEN \
  -e DISCORD_GUILD_ID \
  saseq/discord-mcp:latest
```

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🐋 Docker Compose Installation
    </summary>

#### 1) Clone the repository
```bash
git clone https://github.com/SaseQ/discord-mcp
```

#### 2) Go to the project directory
```bash
cd discord-mcp
```

#### 3) Create local runtime env
```bash
cat > .env <<EOF
SPRING_PROFILES_ACTIVE=http
DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>
DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>
EOF
```

#### 4) Start one shared MCP server container
```bash
docker compose up -d --build
```

#### 5) Verify
```bash
docker ps --filter name=discord-mcp
curl -fsS http://localhost:8085/actuator/health
```

> [!TIP]
> You do not need to set `LOGGING_PATTERN_CONSOLE` manually.
> Logging is configured automatically for both `http` and legacy `stdio` modes.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

Health endpoint (Actuator): `http://localhost:8085/actuator/health`

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🔧 Manual Installation
    </summary>

#### 1) Clone the repository
```bash
git clone https://github.com/SaseQ/discord-mcp
```

#### 2) Build the project

> NOTE: Maven installation is required to use the mvn command. Full instructions can be found [here](https://www.baeldung.com/install-maven-on-windows-linux-mac).

```bash
cd discord-mcp
mvn clean package # The jar file will be available in the /target directory
```

#### 3) Configure AI client
Run the JAR as a long-running server:

```bash
DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN> \
DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID> \
SPRING_PROFILES_ACTIVE=http \
java -jar /absolute/path/to/discord-mcp-1.0.0.jar
```

> NOTE: The `DISCORD_GUILD_ID` environment variable is optional. When provided, it sets a default Discord server ID so any tool that accepts a `guildId` parameter can omit it.

Default MCP endpoint URL (HTTP profile): `http://localhost:8085/mcp`

</details>

## 🔗 Connections

### ► 🗞️ Default config.json Connection

Recommended (HTTP singleton mode):
```json
{
  "mcpServers": {
    "discord-mcp": {
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

Legacy mode (stdio, starts a new process/container per client session):
```json
{
  "mcpServers": {
    "discord-mcp": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>",
        "-e",
        "DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>",
        "saseq/discord-mcp:latest"
      ]
    }
  }
}
```

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        ⌨️ Claude Code Connection
    </summary>

Recommended (HTTP singleton mode):
```bash
claude mcp add discord-mcp --transport http http://localhost:8085/mcp
```

Legacy mode (stdio, starts a new process/container per client session):
```bash
claude mcp add discord-mcp -- docker run --rm -i -e DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN> -e DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID> saseq/discord-mcp:latest
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🤖 Codex CLI Connection
    </summary>

```bash
codex mcp add discord-mcp --url http://localhost:8085/mcp
codex mcp list
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🦞 OpenClaw Connection
    </summary>

Run this command:
```bash
openclaw mcp set discord-mcp '{"url":"http://localhost:8085/mcp","transport":"streamable-http"}'
openclaw mcp list
```

OR

Pasting the following configuration into your OpenClaw `~/.openclaw/config.json` file:
```json
{
  "mcp": {
    "servers": {
      "discord-mcp": {
        "url": "http://localhost:8085/mcp",
        "transport": "streamable-http"
      }
    }
  }
}
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🖲 Cursor Connection
    </summary>

Go to: `Settings` -> `Cursor Settings` -> `MCP` -> `Add new global MCP server`

Pasting the following configuration into your Cursor `~/.cursor/mcp.json` file is the recommended approach. You may also install in a specific project by creating `.cursor/mcp.json` in your project folder. See [Cursor MCP docs](https://docs.cursor.com/context/model-context-protocol) for more info.
```json
{
  "mcpServers": {
    "discord-mcp": {
      "url": "http://localhost:8085/mcp"
    }
  }
}
```

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🚀 n8n Connection
    </summary>

#### Connect in n8n
1. Open n8n and add an **MCP Client** node.
2. Choose **HTTP** or **Streamable HTTP** transport (depending on your n8n version/node options).
3. Set the server URL to: `http://localhost:8085/mcp`
4. Save the node and test the connection.
5. After connecting, you can use the available Discord tools exposed by `discord-mcp` inside your workflow.

#### Notes
- If n8n is running in Docker, `localhost` may point to the n8n container itself, not your host machine.
- In that case, use the Docker service name or another reachable host, for example: `http://discord-mcp:8085/mcp`

</details>

<details>
    <summary style="font-size: 1.35em; font-weight: bold;">
        🖥 Claude Desktop Connection
    </summary>



STDIO local config (Default, legacy):
> Past the following configuration into your Claude Desktop `claude_desktop_config.json` file.
```json
{
  "mcpServers": {
    "discord-mcp": {
      "command": "docker",
      "args": [
        "run",
        "--rm",
        "-i",
        "-e",
        "DISCORD_TOKEN=<YOUR_DISCORD_BOT_TOKEN>",
        "-e",
        "DISCORD_GUILD_ID=<OPTIONAL_DEFAULT_SERVER_ID>",
        "saseq/discord-mcp:latest"
      ]
    }
  }
}
```

Remote MCP Connector:
1. Open Claude Desktop and go to `Settings` -> `Connectors`.
2. Add a custom connector and set MCP URL to your server endpoint (for example `https://<PUBLIC_HOST>/mcp`).
3. Save and reconnect.

> Claude Desktop remote connectors are managed via Connectors UI (not `claude_desktop_config.json`).
> `http://localhost:8085/mcp` is reachable only from your machine. For Claude Desktop remote connectors, expose the endpoint with public HTTPS (for example tunnel/reverse proxy).

</details>


## 🛠️ Available Tools

#### Server Information
- [`get_server_info`](): Get detailed discord server information

#### User Management
- [`get_user_id_by_name`](): Get a Discord user's ID by username in a guild for ping usage `<@id>`
- [`send_private_message`](): Send a private message to a specific user
- [`edit_private_message`](): Edit a private message from a specific user
- [`delete_private_message`](): Delete a private message from a specific user
- [`read_private_messages`](): Read private message history from a specific user (includes attachment metadata, supports `count` 1-100 and optional cursor: `before` or `after` or `around`)

#### Message Management
- [`send_message`](): Send a message to a specific channel
- [`edit_message`](): Edit a message from a specific channel
- [`delete_message`](): Delete a message from a specific channel
- [`read_messages`](): Read message history from a specific channel (includes attachment metadata, supports `count` 1-100 and optional cursor: `before` or `after` or `around`)
- [`add_reaction`](): Add a reaction (emoji) to a specific message
- [`remove_reaction`](): Remove a specified reaction (emoji) from a message

#### Channel Management
- [`create_text_channel`](): Create a new text channel
- [`edit_text_channel`](): Edit settings of a text channel (name, topic, nsfw, slowmode, category, position)
- [`delete_channel`](): Delete a channel
- [`find_channel`](): Find a channel type and ID using name and server ID
- [`list_channels`](): List of all channels
- [`get_channel_info`](): Get detailed information about a channel
- [`move_channel`](): Move a channel to another category and/or change its position

#### Category Management
- [`create_category`](): Create a new category for channels
- [`edit_category`](): Edit a category (rename or move position)
- [`delete_category`](): Delete a category
- [`find_category`](): Find a category ID using name and server ID
- [`list_channels_in_category`](): List of channels in a specific category

#### Webhook Management
- [`create_webhook`](): Create a new webhook on a specific channel
- [`delete_webhook`](): Delete a webhook
- [`list_webhooks`](): List of webhooks on a specific channel
- [`send_webhook_message`](): Send a message via webhook

#### Role Management
- [`list_roles`](): Get a list of all roles on the server with their details
- [`create_role`](): Create a new role on the server
- [`edit_role`](): Modify an existing role's settings
- [`delete_role`](): Permanently delete a role from the server
- [`assign_role`](): Assign a role to a user
- [`remove_role`](): Remove a role from a user

#### Moderation and User Management
- [`kick_member`](): Kicks a member from the server
- [`ban_member`](): Bans a user from the server
- [`unban_member`](): Removes a ban from a user
- [`timeout_member`](): Disables communication for a member for a specified duration
- [`remove_timeout`](): Removes a timeout (unmute) from a member before it expires
- [`set_nickname`](): Changes a member's nickname on the server
- [`get_bans`](): Returns a list of banned users on the server with ban reasons

#### Voice & Stage Channel Management
- [`create_voice_channel`](): Create a new voice channel in a guild
- [`create_stage_channel`](): Create a new stage channel for audio events
- [`edit_voice_channel`](): Edit settings of a voice or stage channel (name, bitrate, user limit, region)
- [`move_member`](): Move a member to another voice channel
- [`disconnect_member`](): Disconnect a member from their current voice channel
- [`modify_voice_state`](): Server mute or deafen a member in voice channels

#### Scheduled Events Management
- [`create_guild_scheduled_event`](): Schedule a new event on the server (voice, stage, or external)
- [`edit_guild_scheduled_event`](): Modify event details or change its status (start, complete, cancel)
- [`delete_guild_scheduled_event`](): Permanently delete a scheduled event
- [`list_guild_scheduled_events`](): List all active and scheduled events on the server
- [`get_guild_scheduled_event_users`](): Get list of users interested in a scheduled event

#### Channel Permission Overwrites
- [`list_channel_permission_overwrites`](): List all permission overwrites for a channel with role/member breakdown
- [`upsert_role_channel_permissions`](): Create or update permission overwrite for a role on a channel
- [`upsert_member_channel_permissions`](): Create or update permission overwrite for a member on a channel
- [`delete_channel_permission_overwrite`](): Delete a permission overwrite for a role or member from a channel

#### Invite Management
- [`create_invite`](): Create a new invite link for a specific channel
- [`list_invites`](): List all active invites on the server with their statistics
- [`delete_invite`](): Delete (revoke) an invite so the link stops working
- [`get_invite_details`](): Get details about a specific invite (works for any public invite)

#### Forum Management
- [`create_forum_channel`](): Create a new forum channel
- [`edit_forum_channel`](): Edit settings of a forum channel (name, topic, nsfw, slowmode, category, position, default sort, default layout)
- [`list_forum_channels`](): List all forum channels in the server
- [`get_forum_channel_info`](): Get detailed information about a forum channel including tags and settings
- [`list_forum_tags`](): List all available tags in a forum channel
- [`create_forum_post`](): Create a new forum post (thread) with an initial message in a forum channel
- [`list_forum_posts`](): List active posts (threads) in a forum channel
- [`modify_forum_post`](): Modify a forum post: lock/unlock, archive/unarchive, pin/unpin, or change applied tags

#### Emoji Management
- [`list_emojis`](): List all custom emojis on the server
- [`get_emoji_details`](): Get detailed information about a specific custom emoji
- [`create_emoji`](): Upload a new custom emoji to the server (base64 or image URL, max 256KB)
- [`edit_emoji`](): Edit an existing emoji's name or role restrictions
- [`delete_emoji`](): Permanently delete a custom emoji from the server

>If `DISCORD_GUILD_ID` is set, the `guildId` parameter becomes optional for all tools above.

<hr>

A more detailed examples can be found in the [Wiki](https://github.com/SaseQ/discord-mcp/wiki).
