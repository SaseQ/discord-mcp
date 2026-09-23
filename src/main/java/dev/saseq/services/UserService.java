package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

@Service
public class UserService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public UserService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild requireGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    /**
     * Resolve members by username (and optional discriminator) using loadMembers plus
     * retrieveMembersByPrefix, not a cold cache-only lookup.
     */
    private List<Member> resolveMembersByName(Guild guild, String name, String discriminator) {
        Map<String, Member> byId = new LinkedHashMap<>();
        for (Member member : loadGuildMembers(guild)) {
            byId.put(member.getId(), member);
        }

        List<Member> matches = filterExactMembers(byId.values(), name, discriminator);
        if (!matches.isEmpty()) {
            return matches;
        }

        for (Member member : retrieveMembersByPrefixSafe(guild, name)) {
            byId.putIfAbsent(member.getId(), member);
        }
        matches = filterExactMembers(byId.values(), name, discriminator);
        if (!matches.isEmpty()) {
            return matches;
        }

        // Unique prefix fallback (e.g. "abnegate" -> "abnegate.") when discriminator is omitted.
        if (discriminator == null) {
            return filterPrefixMembers(byId.values(), name);
        }
        return List.of();
    }

    private List<Member> loadGuildMembers(Guild guild) {
        try {
            List<Member> members = guild.loadMembers().get();
            if (members != null && !members.isEmpty()) {
                return members;
            }
        } catch (RuntimeException ignored) {
            // Fall back to whatever is already cached, then prefix-search in the caller.
        }
        return new ArrayList<>(guild.getMembers());
    }

    private List<Member> retrieveMembersByPrefixSafe(Guild guild, String name) {
        try {
            List<Member> members = guild.retrieveMembersByPrefix(name, 100).get();
            return members != null ? members : List.of();
        } catch (RuntimeException ignored) {
            return List.of();
        }
    }

    private Member retrieveMemberByIdSafe(Guild guild, String userId) {
        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (RuntimeException ignored) {
            return null;
        }
    }

    private List<Member> filterExactMembers(Iterable<Member> members, String name, String discriminator) {
        List<Member> usernameMatches = new ArrayList<>();
        List<Member> displayMatches = new ArrayList<>();
        for (Member member : members) {
            if (!matchesDiscriminator(member, discriminator)) {
                continue;
            }
            if (equalsIgnoreCase(member.getUser().getName(), name)) {
                usernameMatches.add(member);
            } else if (matchesDisplayName(member, name)) {
                displayMatches.add(member);
            }
        }
        return usernameMatches.isEmpty() ? displayMatches : usernameMatches;
    }

    private List<Member> filterPrefixMembers(Iterable<Member> members, String name) {
        List<Member> prefixMatches = new ArrayList<>();
        for (Member member : members) {
            if (startsWithIgnoreCase(member.getUser().getName(), name)
                    || startsWithIgnoreCase(member.getUser().getGlobalName(), name)
                    || startsWithIgnoreCase(member.getNickname(), name)
                    || startsWithIgnoreCase(member.getEffectiveName(), name)) {
                prefixMatches.add(member);
            }
        }
        return prefixMatches;
    }

    private boolean matchesDisplayName(Member member, String name) {
        return equalsIgnoreCase(member.getUser().getGlobalName(), name)
                || equalsIgnoreCase(member.getNickname(), name)
                || equalsIgnoreCase(member.getEffectiveName(), name);
    }

    private boolean matchesDiscriminator(Member member, String discriminator) {
        return discriminator == null || discriminator.equals(member.getUser().getDiscriminator());
    }

    private boolean equalsIgnoreCase(String value, String expected) {
        return value != null && value.equalsIgnoreCase(expected);
    }

    private boolean startsWithIgnoreCase(String value, String prefix) {
        return value != null && value.toLowerCase(Locale.ROOT).startsWith(prefix.toLowerCase(Locale.ROOT));
    }

    private boolean isSnowflake(String value) {
        if (value.length() < 17 || value.length() > 20) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (!Character.isDigit(value.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private String formatMemberLine(Member member) {
        User user = member.getUser();
        StringBuilder line = new StringBuilder();
        line.append("- username=`").append(user.getName()).append("`");
        if (member.getNickname() != null && !member.getNickname().isBlank()) {
            line.append(" nickname=`").append(member.getNickname()).append("`");
        }
        line.append(" displayName=`").append(member.getEffectiveName()).append("`");
        line.append(" id=`").append(user.getId()).append("`");
        if (user.isBot()) {
            line.append(" bot=true");
        }
        return line.toString();
    }

    /**
     * Public tool to retrieve a Discord user's ID by their username (optionally with discriminator) in a guild.
     * Loads/retrieves members instead of relying on a cold member cache.
     *
     * @param username Username (optionally in the format username#discriminator)
     * @param guildId Optional guild/server ID; uses default if not provided
     * @return User ID string if found
     */
    @Tool(name = "get_user_id_by_name", description = "Get a Discord user's ID by username in a guild for ping usage <@id>. Loads/retrieves members rather than using a cold cache.")
    public String getUserIdByName(
            @ToolParam(description = "Discord username (optionally username#discriminator)") String username,
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("username cannot be null");
        }
        Guild guild = requireGuild(guildId);
        String query = username.trim();

        if (isSnowflake(query)) {
            Member byId = retrieveMemberByIdSafe(guild, query);
            if (byId != null) {
                return byId.getUser().getId();
            }
            throw new IllegalArgumentException("No user found with username " + username);
        }

        String name = query;
        String discriminatorLocal = null;
        if (query.contains("#")) {
            int idx = query.lastIndexOf('#');
            name = query.substring(0, idx);
            discriminatorLocal = query.substring(idx + 1);
        }
        if (name.isBlank()) {
            throw new IllegalArgumentException("username cannot be null");
        }

        List<Member> members = resolveMembersByName(guild, name, discriminatorLocal);
        if (members.isEmpty()) {
            throw new IllegalArgumentException("No user found with username " + username);
        }
        if (members.size() > 1) {
            String userList = members.stream()
                    .map(m -> m.getUser().getName() + "#" + m.getUser().getDiscriminator() + " (ID: " + m.getUser().getId() + ")")
                    .collect(Collectors.joining(", "));
            throw new IllegalArgumentException("Multiple users found with username '" + username + "'. List: " + userList + ". Please specify the full username#discriminator.");
        }
        return members.get(0).getUser().getId();
    }

    /**
     * Lists members of a guild with username, nickname, display name, and user ID.
     * Loads the full member roster rather than reading a cold cache.
     *
     * @param guildId Optional guild/server ID; uses default if not provided
     * @return A formatted member list
     */
    @Tool(name = "list_guild_members", description = "List guild members with username, nickname (if any), display name, and user ID. Loads members rather than using a cold cache.")
    public String listGuildMembers(
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        Guild guild = requireGuild(guildId);
        List<Member> members = loadGuildMembers(guild).stream()
                .sorted((a, b) -> a.getUser().getName().compareToIgnoreCase(b.getUser().getName()))
                .toList();
        if (members.isEmpty()) {
            return "No members found in the server.";
        }

        String body = members.stream()
                .map(this::formatMemberLine)
                .collect(Collectors.joining("\n"));
        int advertisedCount = guild.getMemberCount();
        if (advertisedCount > members.size()) {
            return "**Retrieved " + members.size() + " of " + advertisedCount
                    + " members (roster may be incomplete; enable Server Members Intent):**\n" + body;
        }
        return "**Retrieved " + members.size() + " members:**\n" + body;
    }

    /**
     * Sends a private message to a specified Discord user.
     *
     * @param userId  The ID of the user to whom the private message will be sent.
     * @param message The content of the private message.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_private_message", description = "Send a private message to a specific user")
    public String sendPrivateMessage(@ToolParam(description = "Discord user ID") String userId,
                                     @ToolParam(description = "Message content") String message) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message cannot be null");
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        Message sentMessage = user.openPrivateChannel().complete().sendMessage(message).complete();
        return "Message sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    /**
     * Edits a private message sent to a specified Discord user.
     *
     * @param userId     The ID of the user to whom the private message was sent.
     * @param messageId  The ID of the message to be edited.
     * @param newMessage The new content for the message.
     * @return A confirmation message with a link to the edited message.
     */
    @Tool(name = "edit_private_message", description = "Edit a private message from a specific user")
    public String editPrivateMessage(@ToolParam(description = "Discord user ID") String userId,
                                     @ToolParam(description = "Specific message ID") String messageId,
                                     @ToolParam(description = "New message content") String newMessage) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (newMessage == null || newMessage.isEmpty()) {
            throw new IllegalArgumentException("newMessage cannot be null");
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        Message messageById = user.openPrivateChannel().complete().retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        Message editedMessage = messageById.editMessage(newMessage).complete();
        return "Message edited successfully. Message link: " + editedMessage.getJumpUrl();
    }

    /**
     * Deletes a private message sent to a specified Discord user.
     *
     * @param userId    The ID of the user to whom the private message was sent.
     * @param messageId The ID of the message to be deleted.
     * @return A confirmation message indicating the message was deleted successfully.
     */
    @Tool(name = "delete_private_message", description = "Delete a private message from a specific user")
    public String deletePrivateMessage(@ToolParam(description = "Discord user ID") String userId,
                                       @ToolParam(description = "Specific message ID") String messageId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        Message messageById = user.openPrivateChannel().complete().retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        messageById.delete().queue();
        return "Message deleted successfully";
    }

    /**
     * Reads private message history from a specified Discord user.
     *
     * @param userId  The ID of the user from whom to read the private messages.
     * @param count   Optional number of messages to retrieve (default is 100, max is 100).
     * @param before  Optional message ID to fetch messages before this message.
     * @param after   Optional message ID to fetch messages after this message.
     * @param around  Optional message ID to fetch messages around this message.
     * @return A formatted string containing the retrieved private messages.
     */
    @Tool(name = "read_private_messages", description = "Read private message history from a specific user, optionally paginated with before/after/around. Each line includes authorId (Discord snowflake).")
    public String readPrivateMessages(@ToolParam(description = "Discord user ID") String userId,
                                      @ToolParam(description = "Number of messages to retrieve (1-100)", required = false) String count,
                                      @ToolParam(description = "Message ID to fetch messages before this message", required = false) String before,
                                      @ToolParam(description = "Message ID to fetch messages after this message", required = false) String after,
                                      @ToolParam(description = "Message ID to fetch messages around this message", required = false) String around) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        int limit = parseMessageLimit(count);
        validateCursorParameters(before, after, around);

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        var privateChannel = user.openPrivateChannel().complete();
        List<Message> messages;
        if (isProvided(before)) {
            messages = privateChannel.getHistoryBefore(before, limit).complete().getRetrievedHistory();
        } else if (isProvided(after)) {
            messages = privateChannel.getHistoryAfter(after, limit).complete().getRetrievedHistory();
        } else if (isProvided(around)) {
            messages = privateChannel.getHistoryAround(around, limit).complete().getRetrievedHistory();
        } else {
            messages = privateChannel.getHistory().retrievePast(limit).complete();
        }
        List<String> formatedMessages = formatMessages(messages);
        return "**Retrieved " + messages.size() + " messages:** \n" + String.join("\n", formatedMessages);
    }

    private int parseMessageLimit(String count) {
        if (count == null || count.isBlank()) {
            return 100;
        }

        int limit;
        try {
            limit = Integer.parseInt(count);
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException("count must be an integer between 1 and 100");
        }

        if (limit < 1 || limit > 100) {
            throw new IllegalArgumentException("count must be between 1 and 100");
        }
        return limit;
    }

    private void validateCursorParameters(String before, String after, String around) {
        if (before != null && before.isBlank()) {
            throw new IllegalArgumentException("before cannot be blank");
        }
        if (after != null && after.isBlank()) {
            throw new IllegalArgumentException("after cannot be blank");
        }
        if (around != null && around.isBlank()) {
            throw new IllegalArgumentException("around cannot be blank");
        }

        int providedCursors = (isProvided(before) ? 1 : 0)
                + (isProvided(after) ? 1 : 0)
                + (isProvided(around) ? 1 : 0);
        if (providedCursors > 1) {
            throw new IllegalArgumentException("before, after, and around are mutually exclusive; provide only one");
        }
    }

    private boolean isProvided(String value) {
        return value != null && !value.isBlank();
    }

    private User getUserById(String userId) {
        return jda.getGuilds().stream()
                .map(guild -> guild.retrieveMemberById(userId).complete())
                .filter(Objects::nonNull)
                .map(Member::getUser)
                .findFirst()
                .orElse(null);
    }

    private List<String> formatMessages(List<Message> messages) {
        return messages.stream()
                .map(m -> {
                    String authorName = m.getAuthor().getName();
                    String authorId = m.getAuthor().getId();
                    String timestamp = m.getTimeCreated().toString();
                    String content = m.getContentDisplay();
                    String msgId = m.getId();

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("- (ID: %s) **[%s]** (authorId: %s) `%s`: ```%s```", msgId, authorName, authorId, timestamp, content));

                    List<Message.Attachment> attachments = m.getAttachments();
                    if (!attachments.isEmpty()) {
                        sb.append("\n  Attachments:");
                        for (Message.Attachment attachment : attachments) {
                            sb.append("\n    - ").append(formatAttachmentSummary(attachment));
                        }
                    }

                    return sb.toString();
                }).toList();
    }

    private String formatAttachmentSummary(Message.Attachment attachment) {
        return String.format(
                "(Attachment ID: %s) `%s` (%s, %s) URL: %s",
                attachment.getId(),
                attachment.getFileName(),
                formatFileSize(attachment.getSize()),
                attachment.getContentType() != null ? attachment.getContentType() : "unknown",
                attachment.getUrl()
        );
    }

    private String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
