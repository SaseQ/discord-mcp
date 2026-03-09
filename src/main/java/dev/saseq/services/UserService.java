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

import java.util.List;
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
     * Reads the recent private message history from a specified Discord user.
     *
     * @param userId The ID of the user from whom to read the private messages.
     * @param count  Optional number of messages to retrieve (default is 100).
     * @return A formatted string containing the retrieved private messages.
     */
    @Tool(name = "read_private_messages", description = "Read recent message history from a specific user")
    public String readPrivateMessages(@ToolParam(description = "Discord user ID") String userId,
                                      @ToolParam(description = "Number of messages to retrieve", required = false) String count) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        int limit = 100;
        if (count != null) {
            limit = Integer.parseInt(count);
        }

        User user = getUserById(userId);
        if (user == null) {
            throw new IllegalArgumentException("User not found by userId");
        }
        List<Message> messages = user.openPrivateChannel().complete().getHistory().retrievePast(limit).complete();
        List<String> formatedMessages = formatMessages(messages);
        return "**Retrieved " + messages.size() + " messages:** \n" + String.join("\n", formatedMessages);
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
                    String timestamp = m.getTimeCreated().toString();
                    String content = m.getContentDisplay();
                    String msgId = m.getId();

                    StringBuilder sb = new StringBuilder();
                    sb.append(String.format("- (ID: %s) **[%s]** `%s`: ```%s```", msgId, authorName, timestamp, content));

                    List<Message.Attachment> attachments = m.getAttachments();
                    if (!attachments.isEmpty()) {
                        sb.append("\n  Attachments:");
                        for (Message.Attachment attachment : attachments) {
                            sb.append(String.format("\n    - (Attachment ID: %s) `%s` (%s, %s) URL: %s",
                                    attachment.getId(),
                                    attachment.getFileName(),
                                    formatFileSize(attachment.getSize()),
                                    attachment.getContentType() != null ? attachment.getContentType() : "unknown",
                                    attachment.getUrl()));
                        }
                    }

                    return sb.toString();
                }).toList();
    }

    private String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
