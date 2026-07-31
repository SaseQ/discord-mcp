package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.utils.FileUpload;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;
import java.util.List;

@Service
public class MessageService {

    private final JDA jda;

    /**
     * The only directory {@code send_file} may read local paths from. Unset disables local
     * paths entirely. Package-private so tests can set it without Spring.
     */
    @Value("${DISCORD_MCP_FILE_ROOT:}")
    String fileRoot;

    /**
     * The only directory {@code download_attachment} may write to.
     *
     * <p>Deliberately a separate variable with <b>no fallback</b> to {@link #fileRoot}. Reading
     * a directory and writing to it are different grants, and an existing deployment set
     * {@code DISCORD_MCP_FILE_ROOT} to opt into the first one only. Falling back would mean
     * upgrading the jar silently hands an LLM-driven tool write access to a directory chosen
     * for uploads, with no configuration change and nothing to notice. Unset means downloads
     * are refused, which matches how {@code fileRoot} already behaves.
     */
    @Value("${DISCORD_MCP_DOWNLOAD_ROOT:}")
    String downloadRoot;

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
     * Sends a message to a specified Discord channel.
     *
     * @param channelId The ID of the channel where the message will be sent.
     * @param message   The content of the message to be sent.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_message", description = "Send a message to a specific channel")
    public String sendMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Message content") String message) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (message == null || message.isEmpty()) {
            throw new IllegalArgumentException("message cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message sentMessage = channel.sendMessage(message).complete();
        return "Message sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    /**
     * Sends a file (attachment) to a specified Discord channel.
     *
     * @param channelId The ID of the channel where the file will be sent.
     * @param filePath  Absolute path to a local file to upload.
     * @param fileUrl   Direct URL to a file to upload (alternative to filePath).
     * @param fileData  File contents as base64 (Data URI or raw) to upload (alternative to filePath).
     * @param fileName  File name to use for base64 input (and to override the name otherwise).
     * @param message   Optional text content to accompany the file.
     * @return A confirmation message with a link to the sent message.
     */
    @Tool(name = "send_file", description = "Send a file (attachment) to a specific channel. Provide the file as a local filePath OR a direct fileUrl OR base64 fileData (with fileName). Optionally include a text message. Max 50MB — Discord accepts that only on boosted guilds; on an unboosted one it caps at 25MB and rejects larger uploads itself.")
    public String sendFile(@ToolParam(description = "Discord channel ID") String channelId,
                           @ToolParam(description = "Absolute path to a local file to upload", required = false) String filePath,
                           @ToolParam(description = "Direct URL to a file to upload (alternative to filePath)", required = false) String fileUrl,
                           @ToolParam(description = "File contents as base64 Data URI or raw base64 (alternative to filePath; requires fileName)", required = false) String fileData,
                           @ToolParam(description = "File name to use for base64 fileData, or to override the upload name", required = false) String fileName,
                           @ToolParam(description = "Optional text message to accompany the file", required = false) String message) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }

        ResolvedFile resolvedFile = resolveFile(filePath, fileUrl, fileData, fileName);
        if (resolvedFile.bytes().length > MAX_UPLOAD_BYTES) {
            throw new IllegalArgumentException("File exceeds the " + (MAX_UPLOAD_BYTES / (1024 * 1024))
                    + " MB limit (" + (resolvedFile.bytes().length / (1024 * 1024)) + " MB). Discord's own"
                    + " ceiling is lower below boost tier 2, so a smaller file may still be rejected there.");
        }

        FileUpload fileUpload = FileUpload.fromData(resolvedFile.bytes(), resolvedFile.name());
        Message sentMessage;
        if (message != null && !message.isEmpty()) {
            sentMessage = channel.sendFiles(fileUpload).setContent(message).complete();
        } else {
            sentMessage = channel.sendFiles(fileUpload).complete();
        }
        return "File sent successfully. Message link: " + sentMessage.getJumpUrl();
    }

    private record ResolvedFile(byte[] bytes, String name) {
    }

    // Discord's own upload ceiling, which depends on the guild's boost level: 25 MB
    // unboosted, 50 MB at tier 2, 100 MB at tier 3. Set to the tier-2 value so boosted
    // guilds are not blocked by a limit of ours that is stricter than Discord's. An
    // unboosted guild gets Discord's own rejection instead of this one, which is the
    // correct authority for a limit we cannot determine locally.
    private static final int MAX_UPLOAD_BYTES = 50 * 1024 * 1024;

    private ResolvedFile resolveFile(String filePath, String fileUrl, String fileData, String fileName) {
        boolean hasPath = filePath != null && !filePath.isEmpty();
        boolean hasUrl = fileUrl != null && !fileUrl.isEmpty();
        boolean hasData = fileData != null && !fileData.isEmpty();

        int provided = (hasPath ? 1 : 0) + (hasUrl ? 1 : 0) + (hasData ? 1 : 0);
        if (provided == 0) {
            throw new IllegalArgumentException("One of 'filePath', 'fileUrl', or 'fileData' (base64) must be provided");
        }
        if (provided > 1) {
            throw new IllegalArgumentException("Provide only one of 'filePath', 'fileUrl', or 'fileData', not multiple");
        }

        boolean hasName = fileName != null && !fileName.isEmpty();
        if (hasPath) {
            return readLocalFile(filePath, hasName ? fileName : null);
        }
        if (hasUrl) {
            return new ResolvedFile(downloadFile(fileUrl), hasName ? fileName : extractFileNameFromUrl(fileUrl));
        }
        if (!hasName) {
            throw new IllegalArgumentException("'fileName' is required when providing base64 'fileData'");
        }
        return new ResolvedFile(decodeBase64(fileData), fileName);
    }

    private ResolvedFile readLocalFile(String filePath, String overrideName) {
        Path path = resolveWithinAllowedRoot(filePath);
        try {
            // Bounded read: readAllBytes on an attacker-chosen path would OOM the
            // JVM long before the size check below could reject it.
            byte[] bytes;
            try (InputStream in = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
                bytes = in.readNBytes(MAX_UPLOAD_BYTES + 1);
            }
            if (bytes.length > MAX_UPLOAD_BYTES) {
                throw new IllegalArgumentException("File exceeds the " + (MAX_UPLOAD_BYTES / (1024 * 1024)) + " MB limit.");
            }
            String name = overrideName != null ? overrideName : path.getFileName().toString();
            return new ResolvedFile(bytes, name);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read file at filePath: " + e.getMessage());
        }
    }

    /**
     * Confine local file reads to an allowlisted root.
     *
     * <p>Without this, send_file with an absolute filePath will read anything the process can
     * read and post it to Discord. On a host where the service loads secrets from the
     * environment, that is a one-call credential exfiltration path reachable by prompt
     * injection. Set DISCORD_MCP_FILE_ROOT to the only directory uploads may come from.
     *
     * <p>Unset means local filePath uploads are refused entirely. That is the safe default:
     * callers can still use fileUrl or base64 fileData.
     *
     * @return the fully resolved real path, which the caller must read instead of the
     * caller-supplied one
     */
    private Path resolveWithinAllowedRoot(String filePath) {
        Path allowed = allowedRoot();
        Path real;
        try {
            // toRealPath, not normalize: normalize is purely lexical, so a symlink
            // inside the root pointing at /etc/shadow passes a prefix check on the
            // normalized path. Both sides must be resolved for the comparison to mean
            // anything, and the resolved path is what gets opened.
            real = Paths.get(filePath).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("File not found at filePath: " + filePath);
        }
        if (!real.startsWith(allowed) || real.equals(allowed)) {
            throw new IllegalArgumentException("filePath is outside the allowed upload directory");
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException("filePath is not a regular file: " + filePath);
        }
        return real;
    }

    private Path allowedRoot() {
        if (fileRoot == null || fileRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "Local filePath uploads are disabled. Set DISCORD_MCP_FILE_ROOT to an "
                            + "allowed directory, or supply fileUrl or base64 fileData instead.");
        }
        return resolveRoot(fileRoot, "DISCORD_MCP_FILE_ROOT");
    }

    private Path allowedDownloadRoot() {
        if (downloadRoot == null || downloadRoot.isBlank()) {
            throw new IllegalArgumentException(
                    "Attachment downloads are disabled. Set DISCORD_MCP_DOWNLOAD_ROOT to the "
                            + "directory this server may write downloads into. It is separate "
                            + "from DISCORD_MCP_FILE_ROOT on purpose: read and write are "
                            + "different grants.");
        }
        Path root = resolveRoot(downloadRoot, "DISCORD_MCP_DOWNLOAD_ROOT");
        // Existing and writable are different things, and a read-only bind mount is a common
        // way to get the first without the second. Without this probe the failure surfaces only
        // at createTempFile, by which point the whole 100 MB budget may already have been pulled
        // from the CDN to be thrown away. Same reasoning as resolving the root before fetching:
        // spend the cheap check first.
        // Cheap check first: it costs no file at all, and catches the ordinary permissions case
        // so the probe below only runs where it might actually tell us something new. isWritable
        // alone is not enough — it can report true for a read-only bind mount.
        if (!Files.isWritable(root)) {
            throw new IllegalArgumentException(
                    "DISCORD_MCP_DOWNLOAD_ROOT is not writable by this process: " + root
                            + ". Nothing was downloaded.");
        }
        // Every download creates a temp file and renames it. Windows and NFSv4 ACLs grant those
        // separately, so both are probed — and replacement is a third right, deliberately not
        // probed, so a directory that only allows new files still works for first downloads.
        // Untestable on POSIX, where rename needs the same directory write bit as create.
        Path probe = null;
        try {
            probe = Files.createTempFile(root, ".writable-", ".probe");
            Path renamed = root.resolve(probe.getFileName().toString() + ".moved");
            Files.move(probe, renamed);
            probe = renamed;
        } catch (IOException e) {
            // Claims only what the probe observed: a full disk or an fd limit lands here too,
            // and calling either a permissions problem sends someone to fix the wrong thing.
            throw new IllegalArgumentException(
                    "Could not create and rename a file in DISCORD_MCP_DOWNLOAD_ROOT, which "
                            + "saving an attachment requires: " + root
                            + " (" + e.getMessage() + "). Nothing was downloaded.");
        } finally {
            // Best-effort: a failed delete does not mean the directory cannot be written to,
            // which the probe above establishes directly.
            deleteQuietly(probe);
        }
        return root;
    }

    /** Best-effort removal of a scratch file, where failing to clean up is not worth an error. */
    private static void deleteQuietly(Path path) {
        if (path == null) {
            return;
        }
        try {
            Files.deleteIfExists(path);
        } catch (IOException ignored) {
            // Nothing to do, and nothing worth failing the call over.
        }
    }

    private Path resolveRoot(String configured, String variableName) {
        Path root;
        try {
            root = Paths.get(configured).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException(
                    variableName + " does not exist or cannot be resolved: " + configured);
        }
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(variableName + " is not a directory: " + configured);
        }
        // A filesystem root has no name components. Accepting "/" would confine
        // nothing at all and silently re-open the whole vulnerability.
        if (root.getNameCount() == 0) {
            throw new IllegalArgumentException(variableName + " must not be a filesystem root");
        }
        return root;
    }

    private byte[] downloadFile(String url) {
        // Delegated to the shared guard: https only, public host, no redirect
        // following, bounded read. An unguarded fetch here would be an SSRF
        // vector reachable by any MCP client.
        return RemoteFetchGuard.fetch(url, MAX_UPLOAD_BYTES, "file");
    }

    private byte[] decodeBase64(String data) {
        String base64Data;
        if (data.startsWith("data:")) {
            int commaIndex = data.indexOf(',');
            if (commaIndex == -1) {
                throw new IllegalArgumentException("Invalid Data URI format. Expected: data:<mime>;base64,<data>");
            }
            base64Data = data.substring(commaIndex + 1);
        } else {
            base64Data = data;
        }
        try {
            return Base64.getDecoder().decode(base64Data);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid base64 fileData: " + e.getMessage());
        }
    }

    private String extractFileNameFromUrl(String url) {
        String path = url;
        int queryIndex = path.indexOf('?');
        if (queryIndex != -1) {
            path = path.substring(0, queryIndex);
        }
        int lastSlash = path.lastIndexOf('/');
        String name = lastSlash != -1 ? path.substring(lastSlash + 1) : path;
        return name.isEmpty() ? "file" : name;
    }

    /**
     * Edits an existing message in a specified Discord channel.
     *
     * @param channelId  The ID of the channel containing the message.
     * @param messageId  The ID of the message to be edited.
     * @param newMessage The new content for the message.
     * @return A confirmation message with a link to the edited message.
     */
    @Tool(name = "edit_message", description = "Edit a message from a specific channel")
    public String editMessage(@ToolParam(description = "Discord channel ID") String channelId,
                              @ToolParam(description = "Specific message ID") String messageId,
                              @ToolParam(description = "New message content") String newMessage) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }
        if (newMessage == null || newMessage.isEmpty()) {
            throw new IllegalArgumentException("newMessage cannot be null");
        }

        MessageChannel channel = getMessageChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        Message messageById = channel.retrieveMessageById(messageId).complete();
        if (messageById == null) {
            throw new IllegalArgumentException("Message not found by messageId");
        }
        Message editedMessage = messageById.editMessage(newMessage).complete();
        return "Message edited successfully. Message link: " + editedMessage.getJumpUrl();
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
    @Tool(name = "read_messages", description = "Read message history from a specific channel, optionally paginated with before/after/around")
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

    /**
     * Downloads a message's attachments into the allowed file directory.
     *
     * <p>Deliberately takes IDs rather than a URL. The URLs are resolved here, from Discord,
     * so this is not a general fetch-anything-to-disk tool and cannot be pointed at the host's
     * private network. It also sidesteps the reason a caller would want one: Discord CDN links
     * are signed and expire, so a URL copied out of an earlier tool result is usually dead by
     * the time anyone tries to use it. Re-resolving from the message is always fresh.
     *
     * @param channelId    The ID of the channel containing the message.
     * @param messageId    The ID of the message to download attachments from.
     * @param attachmentId Optional ID of a specific attachment (if omitted, downloads all).
     * @return A formatted list of the saved file paths.
     */
    @Tool(name = "download_attachment", description = "Download a message's attachments to the server's download directory (DISCORD_MCP_DOWNLOAD_ROOT) and return the saved paths. Use get_attachment instead if you only need metadata. Max 50MB per file, 100MB per call.")
    public String downloadAttachment(@ToolParam(description = "Discord channel ID") String channelId,
                                     @ToolParam(description = "Discord message ID") String messageId,
                                     @ToolParam(description = "Specific attachment ID (omit to download all)", required = false) String attachmentId) {
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        if (messageId == null || messageId.isEmpty()) {
            throw new IllegalArgumentException("messageId cannot be null");
        }

        // Resolve the root before spending any network calls, so a misconfigured
        // root fails immediately instead of after downloading 50 MB.
        Path root = allowedDownloadRoot();

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
            attachments = attachments.stream()
                    .filter(a -> a.getId().equals(attachmentId))
                    .toList();
            if (attachments.isEmpty()) {
                throw new IllegalArgumentException("Attachment not found by attachmentId");
            }
        }

        assertWithinDownloadLimits(attachments);

        // Per-attachment failures are collected rather than thrown. Throwing on the second of
        // three would abandon the first: already committed to disk, and its path lost with the
        // stack — the same partial-result problem the size preflight exists to avoid, just moved
        // later. Reporting both halves is safe to act on because writes are keyed by attachment
        // ID, so retrying the call rewrites the same paths instead of accumulating duplicates.
        StringBuilder saved = new StringBuilder();
        StringBuilder failed = new StringBuilder();
        int savedCount = 0;
        // Bytes *attempted*, not bytes kept. Counting only what was kept cannot bound anything:
        // the guard rejects an over-allowance body by throwing, so a rejected fetch consumed the
        // wire and left the running total untouched, and the next attachment would be handed the
        // same allowance again. Ten attachments understating their size would each pull the
        // per-file cap before being rejected — the half-gigabyte call this budget exists to rule
        // out. Charging the allowance for a rejection is what makes the budget a real ceiling.
        long attempted = 0;
        int index = 0;
        for (Message.Attachment attachment : attachments) {
            index++;
            long remainingBudget = MAX_DOWNLOAD_BUDGET_BYTES - attempted;
            if (remainingBudget <= 0) {
                // Checked before computing an allowance, so the guard is never called with zero
                // — that would fail with its generic "exceeds the maximum allowed size", which
                // blames the file when the call budget is what ran out.
                failed.append("- ").append(attachments.size() - index + 1)
                        .append(" further attachment(s): not attempted, the call's ")
                        .append(MAX_DOWNLOAD_BUDGET_BYTES / (1024 * 1024))
                        .append(" MB total was already spent.\n");
                break;
            }
            int allowance = (int) Math.min(MAX_DOWNLOAD_FILE_BYTES, remainingBudget);
            try {
                byte[] bytes = RemoteFetchGuard.fetch(attachment.getUrl(), allowance, "attachment");
                attempted += bytes.length;
                Path path = writeIntoAllowedRoot(root, attachment.getId(), attachment.getFileName(), bytes);
                saved.append("- `").append(path).append("` (").append(formatFileSize(bytes.length)).append(")\n");
                savedCount++;
            } catch (RemoteFetchGuard.TooLargeException e) {
                // The body was read up to the allowance before being rejected, so that bandwidth
                // is spent whether or not anything reached disk. Say which limit bound it: the
                // per-file cap and "the rest of this call's budget" are different problems with
                // different fixes, and the guard's own message cannot tell them apart.
                attempted += allowance;
                failed.append("- `").append(attachment.getFileName()).append("` (ID ")
                        .append(attachment.getId()).append("): body exceeded the ")
                        // formatFileSize, not integer MB: the remainder of a budget is routinely
                        // under a megabyte, and "exceeded the 0 MB allowed for it" reads as a bug.
                        .append(formatFileSize(allowance)).append(" allowed for it")
                        .append(allowance < MAX_DOWNLOAD_FILE_BYTES
                                ? " — all that was left of this call's budget. Fetch it on its own."
                                : ", the per-file limit. Its reported size understated the body.")
                        .append("\n");
            } catch (RemoteFetchGuard.TransferFailedException e) {
                // A transfer that died partway still cost what had already arrived. Without this
                // the counter never moves for a mid-transfer reset, and ten of them pull most of
                // the per-file cap each while the budget reads as untouched.
                attempted += e.bytesConsumed();
                failed.append("- `").append(attachment.getFileName()).append("` (ID ")
                        .append(attachment.getId()).append("): transfer failed after ")
                        .append(formatFileSize(e.bytesConsumed())).append(".\n");
            } catch (RuntimeException e) {
                // Everything else — unreachable host, 404, refused scheme — failed before any
                // body arrived, so it does not charge the budget. Charging these would let a
                // couple of 404s cut a call short over failures that cost nothing.
                failed.append("- `").append(attachment.getFileName()).append("` (ID ")
                        .append(attachment.getId()).append("): ").append(reasonOf(e)).append("\n");
            }
        }

        if (savedCount == 0) {
            throw new IllegalArgumentException("No attachments were downloaded.\n" + failed.toString().trim());
        }

        StringBuilder result = new StringBuilder();
        result.append("**Downloaded ").append(savedCount).append(" of ").append(attachments.size())
                .append(" attachment(s) to ").append(root).append(":**\n").append(saved);
        if (failed.length() > 0) {
            result.append("\n**Failed, and not saved. Re-running is safe — it overwrites in place ")
                    .append("rather than duplicating:**\n").append(failed);
        }
        return result.toString().trim();
    }

    /**
     * Rejects an oversized set before the first byte is fetched.
     *
     * <p>Checking after each download would still be correct, but it fails in the worst possible
     * place: the offending file is already in the heap, its siblings are already on disk, and the
     * caller gets an exception instead of the paths — so the files it did write are left in the
     * root unmentioned, for a tool whose entire contract is returning where things landed.
     * {@code getSize()} is metadata already carried on the message, so this costs no network call.
     */
    private void assertWithinDownloadLimits(List<Message.Attachment> attachments) {
        long total = 0;
        for (Message.Attachment attachment : attachments) {
            long size = attachment.getSize();
            if (size > MAX_DOWNLOAD_FILE_BYTES) {
                throw new IllegalArgumentException(String.format(
                        "Attachment `%s` (ID %s) is %s, over the %d MB per-file download limit.",
                        attachment.getFileName(), attachment.getId(), formatFileSize(size),
                        MAX_DOWNLOAD_FILE_BYTES / (1024 * 1024)));
            }
            total += size;
        }
        if (total > MAX_DOWNLOAD_BUDGET_BYTES) {
            throw new IllegalArgumentException(String.format(
                    "The %d attachments total %s, over the %d MB per-call download limit. "
                            + "Pass attachmentId to fetch them one at a time.",
                    attachments.size(), formatFileSize(total), MAX_DOWNLOAD_BUDGET_BYTES / (1024 * 1024)));
        }
    }

    /**
     * Per-file download ceiling.
     *
     * <p>Not {@link #MAX_UPLOAD_BYTES}: that one is Discord's limit on what a standard bot may
     * <i>send</i>, and inbound attachments are not bound by it — a Nitro user or a boosted guild
     * can exceed it. Reusing it would tie the download limit to a send-side ceiling that moves
     * with the guild's boost level, and would have rejected such a file with the guard's
     * generic size message, naming neither the file nor the real reason.
     */
    private static final int MAX_DOWNLOAD_FILE_BYTES = 50 * 1024 * 1024;

    // One message can carry ten attachments, so the per-file cap alone would allow half a
    // gigabyte from a single call. Kept at twice the per-file cap so a normal multi-image
    // post still goes through in one call while a pathological one does not.
    private static final long MAX_DOWNLOAD_BUDGET_BYTES = 2L * MAX_DOWNLOAD_FILE_BYTES;

    /**
     * Writes one attachment into the allowed root under a name derived from Discord's.
     *
     * <p>The filename comes from whoever uploaded the file, so it is untrusted: it is reduced to
     * a single path component here rather than resolved, because {@code root.resolve("../x")}
     * happily escapes. The {@code <attachmentId>-} prefix makes names collision-free <i>across</i>
     * attachments; the same attachment fetched twice overwrites its own file.
     *
     * <p>Written to a temporary file and moved into place rather than written directly. Three
     * things fall out of that: an interrupted or failing write cannot destroy an already-archived
     * copy or leave a truncated one, {@code rename(2)} replaces a symlink at the target rather
     * than following it, and two concurrent calls for the same attachment cannot collide — the
     * HTTP profile is a shared singleton, so that is reachable.
     *
     * @param root the already-validated download root; taken as a parameter so tests can supply
     *             one, which means the confinement below is only as strong as what the single
     *             caller passes. Any second caller must pass {@link #allowedDownloadRoot()} too.
     */
    // Package-private so tests can exercise the untrusted-filename cases without a live message.
    Path writeIntoAllowedRoot(Path root, String attachmentId, String fileName, byte[] bytes) {
        Path target = root.resolve(attachmentId + "-" + sanitizeFileName(fileName));
        if (!target.getParent().equals(root)) {
            // Not merely a backstop for sanitizeFileName: attachmentId is interpolated straight
            // in, unsanitized. It is a Discord snowflake today, so it cannot contain a separator
            // — this check is what makes that an assumption the code verifies rather than one it
            // relies on, and it matters most for the second caller the javadoc above anticipates.
            throw new IllegalArgumentException("Refusing to write outside the allowed directory");
        }
        Path temporary = null;
        try {
            temporary = Files.createTempFile(root, ".download-", ".part");
            Files.write(temporary, bytes, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
            relaxTempFileMode(temporary);
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException e) {
                // Not just AtomicMoveNotSupportedException. Under the ATOMIC_MOVE contract the
                // other options are "ignored" and replacing an existing target is left
                // provider-specific, so a provider may refuse with a plain IOException instead.
                // The default providers all replace — verified on Windows, where the concern is
                // usually raised — so this path is unreachable in practice, but it is the only
                // path that can lose an archived file, which is reason enough to make it safe.
                replaceWithoutAtomicMove(root, temporary, target);
            }
            temporary = null;
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to save attachment: " + e.getMessage());
        } finally {
            // Best effort. A leftover .part file is inert and better than masking the real
            // failure being thrown above.
            deleteQuietly(temporary);
        }
        return target;
    }

    /**
     * Replaces {@code target} without {@code ATOMIC_MOVE}, without risking the file already there.
     *
     * <p>A plain {@code REPLACE_EXISTING} move deletes the target first and then leaves source and
     * target in an undefined state if it fails partway — so the one path that exists to preserve an
     * archived copy could destroy it. The existing file is moved aside first and put back if the
     * replacement does not land, which makes the method's guarantee hold on a provider that refuses
     * atomic moves as well as on the ones that do not.
     */
    private static void replaceWithoutAtomicMove(Path root, Path temporary, Path target) throws IOException {
        Path rescued = null;
        // Only a regular file or a symlink is worth rescuing — those are the shapes this tool
        // writes, so those are the ones that can be a previous download. Anything else at that
        // path was not put there by us and is not ours to move aside; let the replace below fail
        // against it, which is what a directory sitting on the target name should do.
        if (Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(target)) {
            // A unique name per invocation, not "<target>.replaced". Two concurrent calls for
            // the same attachment would otherwise share one rescue path, and the second could
            // delete the copy the first is still relying on to restore — turning a safety net
            // into the way the file disappears. createTempFile then delete leaves the name
            // reserved to this call; the window is inside our own root and harmless.
            rescued = Files.createTempFile(root, ".replaced-", ".bak");
            Files.delete(rescued);
            Files.move(target, rescued, StandardCopyOption.REPLACE_EXISTING);
        }
        try {
            Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
        } catch (IOException e) {
            if (rescued != null && !Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                try {
                    Files.move(rescued, target);
                } catch (IOException restoreFailed) {
                    // Say where it went rather than let it look like the file simply vanished.
                    e.addSuppressed(new IOException(
                            "the previous copy could not be restored and is at " + rescued, restoreFailed));
                }
            }
            throw e;
        }
        if (rescued != null) {
            try {
                Files.deleteIfExists(rescued);
            } catch (IOException cleanupFailed) {
                // The replacement already landed. Failing the call now would report a successful
                // write as a failure and invite a retry that has nothing left to fix.
            }
        }
    }

    /**
     * Widens a temp file from {@code createTempFile}'s owner-only mode to {@code rw-r-----}.
     *
     * <p>{@link Files#createTempFile} deliberately creates 0600, which a direct write would not
     * have. Left alone, every saved attachment would be readable only by the service account —
     * and there is no directory-level arrangement that fixes that after the fact. Directory
     * ownership governs the entry, not the file's contents, and a POSIX default ACL is masked by
     * the mode the file was created with, so 0600 defeats that too. The only ways out are running
     * both components as one user or giving the file a group.
     *
     * <p>Group-readable rather than world-readable: it makes the shared-group deployment work
     * (a setgid download directory gives saved files its group) without publishing Discord
     * attachments to every account on the host. Silently skipped on filesystems without POSIX
     * permissions, where 0600 was never the behaviour anyway.
     */
    private static void relaxTempFileMode(Path temporary) {
        if (!temporary.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            return;
        }
        try {
            Files.setPosixFilePermissions(temporary, PosixFilePermissions.fromString("rw-r-----"));
        } catch (IOException | UnsupportedOperationException e) {
            // The download is still correct at 0600; a consumer that cannot read it is a
            // deployment problem to surface there, not a reason to fail the write here.
        }
    }

    /**
     * A reportable reason for a failure.
     *
     * <p>{@code getMessage()} is null for some RuntimeExceptions, and a literal "null" in text the
     * model reads and acts on is worse than a vague noun.
     */
    private static String reasonOf(RuntimeException e) {
        return e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
    }

    static String sanitizeFileName(String fileName) {
        if (fileName == null || fileName.isBlank()) {
            return "attachment";
        }
        // Allowlist, not a blocklist of separators: this has to hold on both POSIX and
        // Windows, where '\' and ':' are separators too. Letters and digits are matched by
        // Unicode category rather than by ASCII range, so `résumé.pdf` and `写真.png` survive
        // as themselves instead of arriving as `r_sum_.pdf` and `__.png`. That is safe because
        // no separator, control character or bidi override is a letter or a digit, and because
        // the caller independently verifies the resolved target is a direct child of the root.
        StringBuilder sb = new StringBuilder(fileName.length());
        fileName.codePoints().forEach(cp -> {
            if (Character.isLetterOrDigit(cp) || cp == '.' || cp == '_' || cp == '-') {
                sb.appendCodePoint(cp);
            } else {
                sb.append('_');
            }
        });
        // A leading dot would produce a hidden file; a name of only dots would resolve
        // to the directory itself.
        String cleaned = sb.toString().replaceAll("^\\.+", "");
        if (cleaned.isBlank()) {
            return "attachment";
        }
        cleaned = truncateToBytes(cleaned, MAX_NAME_BYTES);
        if (cleaned.isBlank()) {
            return "attachment";
        }
        // Windows reserves these regardless of extension, and opening `CON.png` there does
        // something other than open a file. Harmless today because callers prefix an
        // attachment ID — but the truncation comment above promises not to rely on that, and
        // a promise that holds for one hazard and not another is worse than no promise.
        return WINDOWS_DEVICE_NAMES.matcher(cleaned).matches() ? "_" + cleaned : cleaned;
    }

    private static final java.util.regex.Pattern WINDOWS_DEVICE_NAMES = java.util.regex.Pattern.compile(
            "(?i)(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\\..*)?");

    /**
     * Byte budget for the sanitized part of a saved filename.
     *
     * <p>Bytes, not characters. {@code NAME_MAX} is 255 <i>bytes</i> on ext4, and one CJK
     * character is three of them — so a 120-character limit permits a 360-byte name, which
     * {@code createTempFile} accepts (its own name is short) and the rename then rejects with
     * {@code ENAMETOOLONG}. That combination only became reachable when this method started
     * preserving non-ASCII names, and it would have failed a perfectly ordinary upload.
     *
     * <p>200 leaves room for the caller's {@code <snowflake>-} prefix, which is 20 bytes, and
     * keeps the total comfortably inside 255 without depending on that prefix's exact length.
     */
    private static final int MAX_NAME_BYTES = 200;

    /**
     * Trims to a UTF-8 byte budget from the front, never splitting a character.
     *
     * <p>Keeps the tail because that is where the extension is, then re-strips leading dots and
     * dashes: cutting mid-name can expose a new one, and the result should not depend on callers
     * prefixing an attachment ID even though they do.
     */
    private static String truncateToBytes(String value, int maxBytes) {
        if (value.getBytes(StandardCharsets.UTF_8).length <= maxBytes) {
            return value;
        }
        int[] codePoints = value.codePoints().toArray();
        int bytes = 0;
        int start = codePoints.length;
        while (start > 0) {
            int width = new String(Character.toChars(codePoints[start - 1])).getBytes(StandardCharsets.UTF_8).length;
            if (bytes + width > maxBytes) {
                break;
            }
            bytes += width;
            start--;
        }
        return new String(codePoints, start, codePoints.length - start).replaceAll("^[.\\-]+", "");
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

    // long rather than int: a per-call total can exceed Integer.MAX_VALUE even though
    // any single attachment cannot. Widening is source-compatible with the int callers.
    private String formatFileSize(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format("%.1f KB", bytes / 1024.0);
        if (bytes < 1024 * 1024 * 1024) return String.format("%.1f MB", bytes / (1024.0 * 1024));
        return String.format("%.1f GB", bytes / (1024.0 * 1024 * 1024));
    }
}
