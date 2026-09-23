package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.restaction.ThreadChannelAction;
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

    /**
     * Creates a public thread starting from an existing message in a text or news channel.
     *
     * @param channelId            Parent text/news channel ID containing the message.
     * @param messageId            Message ID to start the thread on.
     * @param name                 Thread name.
     * @param autoArchiveDuration  Optional auto-archive duration in minutes: 60, 1440, 4320, or 10080.
     * @return A confirmation string that includes the new thread channel ID, name, and jump URL.
     */
    @Tool(name = "create_thread_from_message", description = "Create a public thread from an existing message and return the thread channel ID")
    public String createThreadFromMessage(@ToolParam(description = "Parent text/news channel ID containing the message") String channelId,
                                          @ToolParam(description = "Message ID to start the thread on") String messageId,
                                          @ToolParam(description = "Thread name") String name,
                                          @ToolParam(description = "Auto-archive duration in minutes: 60, 1440, 4320, or 10080", required = false) String autoArchiveDuration) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null");
        }

        MessageChannel channel = getThreadParentChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId or does not support creating a thread from a message (use a text or news channel)");
        }

        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }

        ThreadChannel.AutoArchiveDuration archiveDuration = parseAutoArchiveDuration(autoArchiveDuration);
        ThreadChannelAction action = message.createThreadChannel(name);
        if (archiveDuration != null) {
            action.setAutoArchiveDuration(archiveDuration);
        }

        ThreadChannel thread;
        try {
            thread = action.complete();
        } catch (RuntimeException ex) {
            throw new IllegalArgumentException("Failed to create thread from message: " + ex.getMessage(), ex);
        }
        return "Thread created. channelId=" + thread.getId()
                + " name=" + thread.getName()
                + " link=" + thread.getJumpUrl();
    }

    /**
     * Parent channels that can start a public thread from a message: TextChannel or NewsChannel.
     */
    private MessageChannel getThreadParentChannelById(String channelId) {
        TextChannel textChannel = jda.getTextChannelById(channelId);
        if (textChannel != null) {
            return textChannel;
        }
        NewsChannel newsChannel = jda.getNewsChannelById(channelId);
        if (newsChannel != null) {
            return newsChannel;
        }
        return null;
    }

    private ThreadChannel.AutoArchiveDuration parseAutoArchiveDuration(String autoArchiveDuration) {
        if (autoArchiveDuration == null || autoArchiveDuration.isBlank()) {
            return null;
        }
        int minutes;
        try {
            minutes = Integer.parseInt(autoArchiveDuration.trim());
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("autoArchiveDuration must be 60, 1440, 4320, or 10080");
        }
        try {
            return ThreadChannel.AutoArchiveDuration.fromKey(minutes);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("autoArchiveDuration must be 60, 1440, 4320, or 10080");
        }
    }
}
