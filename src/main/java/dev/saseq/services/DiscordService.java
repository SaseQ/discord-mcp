package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Comparator;
import java.util.stream.Collectors;

@Service
public class DiscordService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public DiscordService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    /**
     * Lists all Discord servers (guilds) the bot is a member of.
     *
     * @return A formatted string with the name, ID and member count of each server, marking the default server if one is configured.
     */
    @Tool(name = "list_servers", description = "List all Discord servers the bot is a member of, with their IDs")
    public String listServers() {
        var guilds = jda.getGuilds();
        if (guilds.isEmpty()) {
            return "The bot is not a member of any Discord server. Invite it to a server first.";
        }
        return "Retrieved " + guilds.size() + " servers:\n" + guilds.stream()
                .sorted(Comparator.comparing(Guild::getName, String.CASE_INSENSITIVE_ORDER))
                .map(g -> "- " + g.getName() + " (ID: " + g.getId() + ", Members: " + g.getMemberCount() + ")"
                        + (g.getId().equals(defaultGuildId) ? " [default]" : ""))
                .collect(Collectors.joining("\n"));
    }

    /**
     * Retrieves detailed information about a specified Discord server.
     *
     * @param guildId Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @return A formatted string containing server details, including name, ID, owner, creation date, member count, channel counts, and boost status.
     */
    @Tool(name = "get_server_info", description = "Get detailed discord server information")
    public String getServerInfo(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("Discord server ID cannot be null. Use list_servers to find the ID of the server.");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        String serverName = guild.getName();
        String serverId = guild.getId();
        Member owner = guild.retrieveOwner().complete();
        int totalMembers = guild.getMemberCount();
        int textChannelCount = guild.getTextChannels().size();
        int voiceChannelCount = guild.getVoiceChannels().size();
        int categoryCount = guild.getCategories().size();
        String creationDate = guild.getTimeCreated().toLocalDate().toString();
        int boostCount = guild.getBoostCount();
        String boostTier = guild.getBoostTier().toString();

        return "Server Name: " + serverName + "\n" +
                "Server ID: " + serverId + "\n" +
                "Owner: " + owner.getUser().getName() + "\n" +
                "Created On: " + creationDate + "\n" +
                "Members: " + totalMembers + "\n" +
                "Channels: " +
                " - Text: " + textChannelCount + "\n" +
                " - Voice: " + voiceChannelCount + "\n" +
                "  - Categories: " + categoryCount + "\n" +
                "Boosts: " +
                " - Count: " + boostCount + "\n" +
                " - Tier: " + boostTier;
    }
}