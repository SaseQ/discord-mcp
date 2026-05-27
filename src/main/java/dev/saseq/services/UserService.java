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

    private Guild getGuild(String guildId) {
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
     * Public tool to retrieve a Discord user's ID by their username (optionally with discriminator) in a guild.
     * @param username Username (optionally in the format username#discriminator)
     * @param guildId Optional guild/server ID; uses default if not provided
     * @return User ID string if found, or error message
     */
    @Tool(name = "get_user_id_by_name", description = "Get a Discord user's ID by username in a guild for ping usage <@id>.")
    public String getUserIdByName(
            @ToolParam(description = "Discord username (optionally username#discriminator)") String username,
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        if (username == null || username.isEmpty()) {
            throw new IllegalArgumentException("username cannot be null");
        }
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        String name = username;
        String discriminatorLocal = null;
        if (username.contains("#")) {
            int idx = username.lastIndexOf('#');
            name = username.substring(0, idx);
            discriminatorLocal = username.substring(idx + 1);
        }
        List<Member> members = guild.getMemberCache().getElementsByUsername(name, true);
        if (discriminatorLocal != null) {
            final String finalDiscriminator = discriminatorLocal;
            members = members.stream()
                    .filter(m -> m.getUser().getDiscriminator().equals(finalDiscriminator))
                    .toList();
        }
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
     * Retrieves a guild member by Discord user ID.
     *
     * @param userId  Discord user ID.
     * @param guildId Optional guild/server ID; uses default if not provided.
     * @return Member identity details if found.
     */
    @Tool(name = "get_member_by_id", description = "Get a Discord guild member by stable user ID, including username, global name, nickname, effective name, and roles.")
    public String getMemberById(
            @ToolParam(description = "Discord user ID") String userId,
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        Guild guild = getGuild(guildId);
        Member member = retrieveMemberById(guild, userId, true);
        if (member == null) {
            throw new IllegalArgumentException("No guild member found with userId " + userId);
        }
        return formatMemberDetail(member);
    }

    /**
     * Searches guild members by ID, username, global name, nickname, or effective name.
     *
     * @param query   Search text or Discord user ID.
     * @param count   Optional max candidates to return (default 25, max 100).
     * @param guildId Optional guild/server ID; uses default if not provided.
     * @return Candidate member identity details.
     */
    @Tool(name = "search_members", description = "Search Discord guild members by ID, username, global name, nickname, or effective name. Returns candidates with stable user IDs for reconciliation.")
    public String searchMembers(
            @ToolParam(description = "Search query or Discord user ID") String query,
            @ToolParam(description = "Maximum candidates to return (1-100)", required = false) String count,
            @ToolParam(description = "Discord server ID", required = false) String guildId) {
        if (query == null || query.isBlank()) {
            throw new IllegalArgumentException("query cannot be null");
        }

        Guild guild = getGuild(guildId);
        int limit = parseMemberLimit(count);
        String trimmedQuery = query.trim();
        Map<String, Member> candidates = new LinkedHashMap<>();

        if (isDiscordSnowflake(trimmedQuery)) {
            Member exactMember = retrieveMemberById(guild, trimmedQuery, true);
            if (exactMember != null) {
                candidates.put(exactMember.getId(), exactMember);
            }
        }

        if (candidates.size() < limit) {
            for (Member member : guild.getMemberCache()) {
                if (memberMatches(member, trimmedQuery)) {
                    candidates.put(member.getId(), member);
                }
                if (candidates.size() >= limit) {
                    break;
                }
            }
        }

        if (candidates.size() < limit) {
            try {
                List<Member> prefixMatches = guild.retrieveMembersByPrefix(trimmedQuery, limit).get();
                for (Member member : prefixMatches) {
                    candidates.put(member.getId(), member);
                    if (candidates.size() >= limit) {
                        break;
                    }
                }
            } catch (RuntimeException ignored) {
                // Return exact/cache matches even if live prefix lookup is unavailable.
            }
        }

        if (candidates.isEmpty()) {
            return "No members found for query " + trimmedQuery;
        }

        List<String> formattedMembers = new ArrayList<>();
        for (Member member : candidates.values()) {
            formattedMembers.add(formatMemberSummary(member, false));
        }
        return "**Found " + formattedMembers.size() + " member candidate(s):**\n" + String.join("\n", formattedMembers);
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
    @Tool(name = "read_private_messages", description = "Read private message history from a specific user, optionally paginated with before/after/around")
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
        for (Guild guild : jda.getGuilds()) {
            Member member = retrieveMemberById(guild, userId, true);
            if (member != null) {
                return member.getUser();
            }
        }
        return null;
    }

    private Member retrieveMemberById(Guild guild, String userId, boolean refresh) {
        try {
            return guild.retrieveMemberById(userId).useCache(!refresh).complete();
        } catch (RuntimeException ex) {
            return null;
        }
    }

    private int parseMemberLimit(String count) {
        if (count == null || count.isBlank()) {
            return 25;
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

    private boolean isDiscordSnowflake(String value) {
        return value.matches("\\d{17,20}");
    }

    private boolean memberMatches(Member member, String query) {
        String normalizedQuery = query.toLowerCase(Locale.ROOT);
        return containsIgnoreCase(member.getId(), normalizedQuery)
                || containsIgnoreCase(member.getUser().getName(), normalizedQuery)
                || containsIgnoreCase(member.getUser().getGlobalName(), normalizedQuery)
                || containsIgnoreCase(member.getNickname(), normalizedQuery)
                || containsIgnoreCase(member.getEffectiveName(), normalizedQuery);
    }

    private boolean containsIgnoreCase(String value, String normalizedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(normalizedQuery);
    }

    private String formatMemberDetail(Member member) {
        return "**Member found:**\n" + formatMemberSummary(member, true);
    }

    private String formatMemberSummary(Member member, boolean includeDetails) {
        User user = member.getUser();
        String summary = String.format(
                "- User ID: %s | Username: %s | Global: %s | Nickname: %s | Effective: %s | Bot: %s",
                user.getId(),
                user.getName(),
                formatNullable(user.getGlobalName()),
                formatNullable(member.getNickname()),
                member.getEffectiveName(),
                user.isBot()
        );
        if (!includeDetails) {
            return summary;
        }

        String roles = member.getRoles().stream()
                .map(role -> role.getName() + " (ID: " + role.getId() + ")")
                .collect(Collectors.joining(", "));
        if (roles.isBlank()) {
            roles = "none";
        }
        return summary + String.format(" | Joined: %s | Roles: %s", member.getTimeJoined(), roles);
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
                    sb.append(String.format(
                            "- (ID: %s) **[%s]** (Author ID: %s) `%s`: ```%s```",
                            msgId,
                            authorName,
                            authorId,
                            timestamp,
                            content
                    ));

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

    private String formatNullable(String value) {
        return value == null || value.isBlank() ? "none" : value;
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
