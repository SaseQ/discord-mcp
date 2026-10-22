package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.attribute.IThreadContainer;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
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
     * Lists the threads of a single channel: its active threads plus its most recent archived public threads.
     * Works for every channel type that can contain threads (text, news, forum and media channels).
     *
     * @param channelId The ID of the parent channel.
     * @return A formatted string listing each thread with its ID, status, creation time and jump URL.
     */
    @Tool(name = "list_threads", description = "List the threads of one channel (text, news, forum or media): active threads plus up to 100 recent archived public threads. A returned thread ID can be used as channelId in read_messages and send_message")
    public String listThreads(@ToolParam(description = "ID of the parent channel that contains the threads") String channelId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }

        GuildChannel guildChannel = jda.getGuildChannelById(channelId);
        if (!(guildChannel instanceof IThreadContainer container)) {
            throw new IllegalArgumentException("Channel not found by channelId or it cannot contain threads");
        }

        List<ThreadChannel> threads = new ArrayList<>(container.getThreadChannels());
        String archivedNote = "";
        try {
            for (ThreadChannel archived : container.retrieveArchivedPublicThreadChannels().limit(100).complete()) {
                if (threads.stream().noneMatch(t -> t.getId().equals(archived.getId()))) {
                    threads.add(archived);
                }
            }
        } catch (InsufficientPermissionException e) {
            archivedNote = "\n(Archived threads were not listed: the bot lacks the " + e.getPermission().getName() + " permission)";
        }

        if (threads.isEmpty()) {
            return "No threads found in channel " + guildChannel.getName() + " (ID: " + channelId + ")." + archivedNote;
        }

        return "Retrieved " + threads.size() + " threads in #" + guildChannel.getName() + ":\n" +
                threads.stream()
                        .map(t -> String.format(
                                "- **%s** (ID: %s)\n" +
                                        "  • Archived: %s\n" +
                                        "  • Locked: %s\n" +
                                        "  • Created: %s\n" +
                                        "  • Messages: %d\n" +
                                        "  • Jump URL: %s",
                                t.getName(),
                                t.getId(),
                                t.isArchived(),
                                t.isLocked(),
                                t.getTimeCreated(),
                                t.getMessageCount(),
                                t.getJumpUrl()))
                        .collect(Collectors.joining("\n")) + archivedNote;
    }

    /**
     * Retrieves detailed information about a specific thread.
     *
     * @param threadId The ID of the thread.
     * @return A formatted string with the thread's name, parent channel, owner, state and counters.
     */
    @Tool(name = "get_thread_info", description = "Get detailed information about a thread: parent channel, owner, archived/locked state, creation time, message and member counts")
    public String getThreadInfo(@ToolParam(description = "Thread ID") String threadId) {
        if (threadId == null || threadId.isEmpty()) {
            throw new IllegalArgumentException("threadId cannot be null");
        }

        ThreadChannel thread = jda.getThreadChannelById(threadId);
        if (thread == null) {
            throw new IllegalArgumentException("Thread not found by threadId");
        }

        String owner = thread.getOwner() != null
                ? thread.getOwner().getUser().getName() + " (ID: " + thread.getOwnerId() + ")"
                : "unknown (ID: " + thread.getOwnerId() + ")";

        return String.format(
                "Thread: **%s** (ID: %s)\n" +
                        "• Type: %s\n" +
                        "• Parent channel: #%s (ID: %s)\n" +
                        "• Owner: %s\n" +
                        "• Created: %s\n" +
                        "• Archived: %s\n" +
                        "• Locked: %s\n" +
                        "• Messages: %d\n" +
                        "• Members: %d\n" +
                        "• Jump URL: %s",
                thread.getName(),
                thread.getId(),
                thread.getType().name(),
                thread.getParentChannel().getName(),
                thread.getParentChannel().getId(),
                owner,
                thread.getTimeCreated(),
                thread.isArchived(),
                thread.isLocked(),
                thread.getMessageCount(),
                thread.getMemberCount(),
                thread.getJumpUrl());
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
