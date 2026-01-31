package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ThreadService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ThreadService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    /**
     * Lists all active threads in a specified Discord server.
     *
     * @param guildId Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @return A formatted string listing all active threads in the server, including their name, ID, and parent channel.
     */
    @Tool(name = "list_active_threads", description = "List all active threads in the server")
    public String listActiveThreads(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        // Retrieve active threads from Discord API
        List<ThreadChannel> threads = guild.retrieveActiveThreads().complete();

        if (threads.isEmpty()) {
            return "No active threads found in the server.";
        }

        return "Retrieved " + threads.size() + " active threads:\n" +
                threads.stream()
                        .map(t -> {
                            String parentName = t.getParentChannel() != null ? t.getParentChannel().getName() : "unknown";
                            String archived = t.isArchived() ? " (archived)" : "";
                            return "- " + t.getName() + " (ID: " + t.getId() + ") in #" + parentName + archived;
                        })
                        .collect(Collectors.joining("\n"));
    }
}
