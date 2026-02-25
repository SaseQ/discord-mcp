package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.emoji.RichCustomEmoji;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class EmojiService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public EmojiService(JDA jda) {
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

    @Tool(name = "list_emojis", description = "List all custom emojis on the server")
    public String listEmojis(
            @ToolParam(description = "Discord server ID", required = false) String guildId) {

        Guild guild = getGuild(guildId);
        List<RichCustomEmoji> emojis = guild.retrieveEmojis().complete();

        if (emojis.isEmpty()) {
            return "No custom emojis found on this server.";
        }

        return "Retrieved " + emojis.size() + " custom emojis:\n" +
                emojis.stream()
                        .map(e -> "- **" + e.getName() + "** (ID: " + e.getId() + ")" +
                                " | Animated: " + e.isAnimated() +
                                (e.getRoles().isEmpty() ? "" : " | Roles: " +
                                        e.getRoles().stream().map(Role::getName).collect(Collectors.joining(", "))))
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_emoji_details", description = "Get detailed information about a specific custom emoji")
    public String getEmojiDetails(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the emoji") String emojiId) {

        Guild guild = getGuild(guildId);
        if (emojiId == null || emojiId.isEmpty()) {
            throw new IllegalArgumentException("emojiId cannot be null");
        }

        RichCustomEmoji emoji = guild.retrieveEmojiById(emojiId).complete();

        StringBuilder sb = new StringBuilder();
        sb.append("Emoji: **").append(emoji.getName()).append("** (ID: ").append(emoji.getId()).append(")\n");
        sb.append("  • Animated: ").append(emoji.isAnimated()).append("\n");
        sb.append("  • Managed: ").append(emoji.isManaged()).append("\n");
        sb.append("  • Available: ").append(emoji.isAvailable()).append("\n");
        sb.append("  • Image URL: ").append(emoji.getImageUrl());
        if (!emoji.getRoles().isEmpty()) {
            sb.append("\n  • Restricted to roles: ").append(
                    emoji.getRoles().stream()
                            .map(r -> r.getName() + " (ID: " + r.getId() + ")")
                            .collect(Collectors.joining(", ")));
        }
        return sb.toString();
    }

    @Tool(name = "create_emoji", description = "Upload a new custom emoji to the server. Provide image as base64 (Data URI or raw) OR a direct image URL. Max 256KB")
    public String createEmoji(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Emoji name (2-32 chars, alphanumeric and underscores only)") String name,
            @ToolParam(description = "Image as base64 Data URI (e.g. data:image/png;base64,...) or raw base64 string", required = false) String image,
            @ToolParam(description = "Direct URL to the image file (alternative to base64 image)", required = false) String imageUrl,
            @ToolParam(description = "Comma-separated role IDs to restrict emoji usage (empty = everyone)", required = false) String roles) {

        Guild guild = getGuild(guildId);
        name = validateAndNormalizeName(name);

        byte[] imageBytes = resolveImage(image, imageUrl);

        if (imageBytes.length > 256 * 1024) {
            throw new IllegalArgumentException("Image exceeds 256 KB limit (" + (imageBytes.length / 1024) + " KB). Please compress or resize the image.");
        }

        net.dv8tion.jda.api.entities.Icon icon = net.dv8tion.jda.api.entities.Icon.from(imageBytes);

        Role[] roleArray = parseRoles(guild, roles);
        RichCustomEmoji emoji = guild.createEmoji(name, icon, roleArray).complete();

        return "Created emoji **" + emoji.getName() + "** (ID: " + emoji.getId() + ")" +
                "\n  • Animated: " + emoji.isAnimated() +
                "\n  • Image URL: " + emoji.getImageUrl();
    }

    @Tool(name = "edit_emoji", description = "Edit an existing emoji's name or role restrictions")
    public String editEmoji(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the emoji to edit") String emojiId,
            @ToolParam(description = "New name for the emoji", required = false) String name,
            @ToolParam(description = "Comma-separated role IDs to restrict usage (empty string = unrestrict for everyone)", required = false) String roles) {

        Guild guild = getGuild(guildId);
        if (emojiId == null || emojiId.isEmpty()) {
            throw new IllegalArgumentException("emojiId cannot be null");
        }

        RichCustomEmoji emoji = guild.retrieveEmojiById(emojiId).complete();
        var manager = emoji.getManager();

        if (name != null && !name.isEmpty()) {
            manager.setName(validateAndNormalizeName(name));
        }
        if (roles != null) {
            if (roles.isEmpty()) {
                manager.setRoles(null);
            } else {
                manager.setRoles(Arrays.stream(roles.split(","))
                        .map(String::trim)
                        .map(id -> {
                            Role role = guild.getRoleById(id);
                            if (role == null) throw new IllegalArgumentException("Role not found: " + id);
                            return role;
                        })
                        .collect(Collectors.toSet()));
            }
        }

        manager.complete();
        RichCustomEmoji updated = guild.retrieveEmojiById(emojiId).complete();
        return "Updated emoji **" + updated.getName() + "** (ID: " + updated.getId() + ")";
    }

    @Tool(name = "delete_emoji", description = "Permanently delete a custom emoji from the server")
    public String deleteEmoji(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the emoji to delete") String emojiId) {

        Guild guild = getGuild(guildId);
        if (emojiId == null || emojiId.isEmpty()) {
            throw new IllegalArgumentException("emojiId cannot be null");
        }

        RichCustomEmoji emoji = guild.retrieveEmojiById(emojiId).complete();
        String name = emoji.getName();
        emoji.delete().complete();
        return "Deleted emoji **" + name + "** (ID: " + emojiId + ")";
    }

    private String validateAndNormalizeName(String name) {
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("Emoji name cannot be null or empty");
        }
        name = name.replaceAll("\\s+", "_");
        if (name.length() < 2 || name.length() > 32) {
            throw new IllegalArgumentException("Emoji name must be between 2 and 32 characters");
        }
        if (!name.matches("[a-zA-Z0-9_]+")) {
            throw new IllegalArgumentException("Emoji name can only contain letters, numbers, and underscores");
        }
        return name;
    }

    private byte[] resolveImage(String image, String imageUrl) {
        boolean hasImage = image != null && !image.isEmpty();
        boolean hasUrl = imageUrl != null && !imageUrl.isEmpty();

        if (!hasImage && !hasUrl) {
            throw new IllegalArgumentException("Either 'image' (base64) or 'imageUrl' (direct link) must be provided");
        }
        if (hasImage && hasUrl) {
            throw new IllegalArgumentException("Provide only one of 'image' or 'imageUrl', not both");
        }

        return hasUrl ? downloadImage(imageUrl) : decodeDataUri(image);
    }

    private byte[] downloadImage(String url) {
        try {
            return URI.create(url).toURL().openStream().readAllBytes();
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to download image from URL: " + e.getMessage());
        }
    }

    private byte[] decodeDataUri(String dataUri) {
        String base64Data;
        if (dataUri.startsWith("data:")) {
            int commaIndex = dataUri.indexOf(',');
            if (commaIndex == -1) {
                throw new IllegalArgumentException("Invalid Data URI format. Expected: data:image/png;base64,<data>");
            }
            base64Data = dataUri.substring(commaIndex + 1);
        } else {
            base64Data = dataUri;
        }
        return Base64.getDecoder().decode(base64Data);
    }

    private Role[] parseRoles(Guild guild, String roles) {
        if (roles == null || roles.isEmpty()) {
            return new Role[0];
        }
        return Arrays.stream(roles.split(","))
                .map(String::trim)
                .filter(id -> !id.isEmpty())
                .map(id -> {
                    Role role = guild.getRoleById(id);
                    if (role == null) throw new IllegalArgumentException("Role not found: " + id);
                    return role;
                })
                .toArray(Role[]::new);
    }
}
