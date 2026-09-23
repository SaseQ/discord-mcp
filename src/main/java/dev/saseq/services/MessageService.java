package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.messages.MessageCreateBuilder;
import net.dv8tion.jda.api.utils.messages.MessageEditBuilder;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class MessageService {

    private final JDA jda;

    public MessageService(JDA jda) {
        this.jda = jda;
    }

    /**
     * Helper method to get a MessageChannel by ID, checking both text channels and thread channels.
     */
    private MessageChannel getMessageChannelById(String channelId) {
        // First try text channel
        TextChannel textChannel = jda.getTextChannelById(channelId);
        if (textChannel != null) {
            return textChannel;
        }
        // Then try news/announcement channel
        NewsChannel newsChannel = jda.getNewsChannelById(channelId);
        if (newsChannel != null) {
            return newsChannel;
        }
        // Then try thread channel
        ThreadChannel threadChannel = jda.getThreadChannelById(channelId);
        if (threadChannel != null) {
            return threadChannel;
        }
        return null;
    }

    /**
     * Sends a message to a specified Discord channel, optionally as a reply, with embeds and/or files.
     *
     * @param channelId         The ID of the channel where the message will be sent.
     * @param message           The content of the message to be sent (optional when embeds or attachments are provided).
     * @param replyToMessageId  Optional ID of the message to reply to (Discord message_reference).
     * @param failIfNotExists   Optional true/false flag. When replying, defaults to true and maps
     *                          to JDA failOnInvalidReply so a missing referenced message fails the send.
     * @param embedsJson        Optional JSON array of Discord embed objects.
     * @param attachmentPaths   Optional JSON array of absolute local file paths (or a single absolute path).
     * @param attachmentsJson   Optional JSON array of { path } and/or { filename, base64 } / data-URI attachments.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_message", description = "Send a message to a specific channel. Optionally reply with replyToMessageId, attach embeds via embedsJson, and/or upload files via attachmentPaths or attachmentsJson.")
    public String sendMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Message content (optional when embedsJson or attachments are provided)", required = false) String message,
                              @ToolParam(description = "Message ID to reply to (sets Discord message_reference)", required = false) String replyToMessageId,
                              @ToolParam(description = "Fail if the referenced reply message does not exist (true/false, default true when replying)", required = false) String failIfNotExists,
                              @ToolParam(description = "JSON array of Discord embed objects (title, titleUrl/url, description, color, footer, author, fields, thumbnailUrl, imageUrl, timestamp)", required = false) String embedsJson,
                              @ToolParam(description = "JSON array of absolute local file paths to attach, or a single absolute path", required = false) String attachmentPaths,
                              @ToolParam(description = "JSON array of attachments: { path } and/or { filename, base64 } or data-URI strings", required = false) String attachmentsJson) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }

        List<MessageEmbed> embeds = EmbedJsonParser.parse(embedsJson);
        List<FileUpload> uploads = AttachmentParser.parse(attachmentPaths, attachmentsJson);
        try {
            boolean hasContent = isProvided(message);
            if (!hasContent && embeds.isEmpty() && uploads.isEmpty()) {
                throw new IllegalArgumentException("message, embedsJson, or attachments are required");
            }

            MessageChannel channel = getMessageChannelById(channelId);
            if (channel == null) {
                throw new IllegalArgumentException("Channel not found by channelId");
            }

            MessageCreateBuilder builder = new MessageCreateBuilder();
            if (hasContent) {
                builder.setContent(message);
            }
            if (!embeds.isEmpty()) {
                builder.setEmbeds(embeds);
            }
            if (!uploads.isEmpty()) {
                builder.setFiles(uploads);
            }

            MessageCreateAction action = channel.sendMessage(builder.build());
            if (replyToMessageId != null && !replyToMessageId.isBlank()) {
                action.setMessageReference(replyToMessageId);
                // JDA defaults failOnInvalidReply to false; when a reply is requested, default to true.
                boolean failOnInvalidReply = failIfNotExists == null
                        || failIfNotExists.isBlank()
                        || Boolean.parseBoolean(failIfNotExists);
                action.failOnInvalidReply(failOnInvalidReply);
            }
            Message sentMessage = action.complete();
            return "Message sent successfully. Message link: " + sentMessage.getJumpUrl();
        } finally {
            AttachmentParser.closeQuietly(uploads);
        }
    }

    /**
     * Edits an existing message in a specified Discord channel.
     *
     * @param channelId        The ID of the channel containing the message.
     * @param messageId        The ID of the message to be edited.
     * @param newMessage       The new content for the message (optional when embeds or attachments are provided).
     * @param embedsJson       Optional JSON array of Discord embed objects. Replaces existing embeds when provided.
     * @param attachmentPaths  Optional JSON array of absolute local file paths (or a single absolute path).
     * @param attachmentsJson  Optional JSON array of { path } and/or { filename, base64 } / data-URI attachments.
     * @return A confirmation message with a link to the edited message.
     */
    @Tool(name = "edit_message", description = "Edit a message from a specific channel. Optional embedsJson replaces embeds; optional attachmentPaths/attachmentsJson replace files.")
    public String editMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Specific message ID") String messageId,
                              @ToolParam(description = "New message content (optional when embedsJson or attachments are provided)", required = false) String newMessage,
                              @ToolParam(description = "JSON array of Discord embed objects (title, titleUrl/url, description, color, footer, author, fields, thumbnailUrl, imageUrl, timestamp)", required = false) String embedsJson,
                              @ToolParam(description = "JSON array of absolute local file paths to attach, or a single absolute path. Replaces existing attachments.", required = false) String attachmentPaths,
                              @ToolParam(description = "JSON array of attachments: { path } and/or { filename, base64 } or data-URI strings. Replaces existing attachments.", required = false) String attachmentsJson) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        boolean hasEmbedsJson = isProvided(embedsJson);
        boolean hasAttachmentParams = isProvided(attachmentPaths) || isProvided(attachmentsJson);
        List<MessageEmbed> embeds = EmbedJsonParser.parse(embedsJson);
        List<FileUpload> uploads = AttachmentParser.parse(attachmentPaths, attachmentsJson);
        try {
            boolean hasContent = isProvided(newMessage);
            if (!hasContent && !hasEmbedsJson && !hasAttachmentParams) {
                throw new IllegalArgumentException("newMessage, embedsJson, or attachments are required");
            }
            if (!hasContent && embeds.isEmpty() && uploads.isEmpty()) {
                throw new IllegalArgumentException("newMessage, embedsJson, or attachments are required");
            }

            MessageChannel channel = getMessageChannelById(channelId);
            if (channel == null) {
                throw new IllegalArgumentException("Channel not found by channelId");
            }
            Message messageById = channel.retrieveMessageById(messageId).complete();
            if (messageById == null) {
                throw new IllegalArgumentException("Message not found by messageId");
            }

            MessageEditBuilder builder = new MessageEditBuilder();
            if (newMessage != null) {
                builder.setContent(newMessage.isBlank() ? "" : newMessage);
            }
            if (hasEmbedsJson) {
                builder.setEmbeds(embeds);
            }
            if (hasAttachmentParams) {
                builder.setAttachments(uploads);
            }
            Message editedMessage = messageById.editMessage(builder.build()).complete();
            return "Message edited successfully. Message link: " + editedMessage.getJumpUrl();
        } finally {
            AttachmentParser.closeQuietly(uploads);
        }
    }

    /**
     * Deletes a message from a specified Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message to be deleted.
     * @return A confirmation message indicating the message was deleted successfully.
     */
    @Tool(name = "delete_message", description = "Delete a message from a specific channel")
    public String deleteMessage(@ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Specific message ID") String messageId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message messageById = channel.retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        messageById.delete().queue();
        return "Message deleted successfully";
    }

    /**
     * Reads message history from a specified Discord channel.
     *
     * @param channelId The ID of the channel from which to read messages.
     * @param count     Optional number of messages to retrieve (default is 100, max is 100).
     * @param before    Optional message ID to fetch messages before this message.
     * @param after     Optional message ID to fetch messages after this message.
     * @param around    Optional message ID to fetch messages around this message.
     * @return A formatted string containing the retrieved messages.
     */
    @Tool(name = "read_messages", description = "Read message history from a specific channel, optionally paginated with before/after/around. Each line includes authorId (Discord snowflake).")
    public String readMessages(@ToolParam(description = "Discord channel ID") String channelId,
                               @ToolParam(description = "Number of messages to retrieve (1-100)", required = false) String count,
                               @ToolParam(description = "Message ID to fetch messages before this message", required = false) String before,
                               @ToolParam(description = "Message ID to fetch messages after this message", required = false) String after,
                               @ToolParam(description = "Message ID to fetch messages around this message", required = false) String around) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        int limit = parseMessageLimit(count);
        validateCursorParameters(before, after, around);

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        List<Message> messages;
        if (isProvided(before)) {
            messages = channel.getHistoryBefore(before, limit).complete().getRetrievedHistory();
        } else if (isProvided(after)) {
            messages = channel.getHistoryAfter(after, limit).complete().getRetrievedHistory();
        } else if (isProvided(around)) {
            messages = channel.getHistoryAround(around, limit).complete().getRetrievedHistory();
        } else {
            messages = channel.getHistory().retrievePast(limit).complete();
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

    /**
     * Adds a reaction (emoji) to a specific message in a Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message to which the reaction will be added.
     * @param emoji     The emoji to add as a reaction (can be a Unicode character or a custom emoji string).
     * @return A confirmation message with a link to the message that was reacted to.
     */
    @Tool(name = "add_reaction", description = "Add a reaction (emoji) to a specific message")
    public String addReaction(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Discord message ID") String messageId,
                              @ToolParam(description = "Emoji (Unicode or string)") String emoji) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (emoji == null || emoji.isEmpty()) {
            throw new IllegalArgumentException("emoji cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        message.addReaction(Emoji.fromUnicode(emoji)).queue();
        return "Added reaction successfully. Message link: " + message.getJumpUrl();
    }

    /**
     * Removes a specified reaction (emoji) from a message in a Discord channel.
     *
     * @param channelId The ID of the channel containing the message.
     * @param messageId The ID of the message from which the reaction will be removed.
     * @param emoji     The emoji to remove from the message (can be a Unicode character or a custom emoji string).
     * @return A confirmation message with a link to the message.
     */
    @Tool(name = "remove_reaction", description = "Remove a specified reaction (emoji) from a message")
    public String removeReaction(@ToolParam(description = "Discord channel ID") String channelId,
                                 @ToolParam(description = "Discord message ID") String messageId,
                                 @ToolParam(description = "Emoji (Unicode or string)") String emoji) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (emoji == null || emoji.isEmpty()) {
            throw new IllegalArgumentException("emoji cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        message.removeReaction(Emoji.fromUnicode(emoji)).queue();
        return "Removed reaction successfully. Message link: " + message.getJumpUrl();
    }

    /**
     * Retrieves attachment metadata from a specific message in a Discord channel.
     *
     * @param channelId    The ID of the channel containing the message.
     * @param messageId    The ID of the message to retrieve attachments from.
     * @param attachmentId Optional ID of a specific attachment (if omitted, returns all).
     * @return A formatted string containing attachment metadata.
     */
    @Tool(name = "get_attachment", description = "Get attachment metadata (filename, size, content type, URLs) from a specific message. Returns info only, does not download files.")
    public String getAttachment(@ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Discord message ID") String messageId,
                                @ToolParam(description = "Specific attachment ID (omit to get all attachments)", required = false) String attachmentId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message message = channel.retrieveMessageById(messageId).complete();
        if (message == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }

        List<Message.Attachment> attachments = message.getAttachments();
        if (attachments.isEmpty()) {
            return "This message has no attachments.";
        }

        if (attachmentId != null && !attachmentId.isEmpty()) {
            Message.Attachment attachment = attachments.stream()
                    .filter(a -> a.getId().equals(attachmentId))
                    .findFirst()
                    .orElse(null);
            if (attachment == null) {
                throw new IllegalArgumentException("Attachment not found by attachmentId");
            }
            return formatAttachmentDetail(attachment);
        }

        StringBuilder sb = new StringBuilder();
        sb.append("**Found ").append(attachments.size()).append(" attachment(s):**\n");
        for (Message.Attachment attachment : attachments) {
            sb.append(formatAttachmentDetail(attachment)).append("\n");
        }
        return sb.toString().trim();
    }

    private String formatAttachmentDetail(Message.Attachment attachment) {
        return String.format(
                "- %s\n  Proxy URL: %s",
                formatAttachmentSummary(attachment),
                attachment.getProxyUrl()
        );
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

    private String formatFileSize(int bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
