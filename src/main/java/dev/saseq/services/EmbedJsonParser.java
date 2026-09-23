package dev.saseq.services;

import net.dv8tion.jda.api.EmbedBuilder;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageEmbed;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.time.temporal.TemporalAccessor;
import java.util.ArrayList;
import java.util.List;

/**
 * Parses MCP-friendly embed JSON into JDA {@link MessageEmbed}s.
 */
final class EmbedJsonParser {

    private EmbedJsonParser() {
    }

    static List<MessageEmbed> parse(String embedsJson) {
        if (embedsJson == null || embedsJson.isBlank()) {
            return List.of();
        }

        String trimmed = embedsJson.trim();
        DataArray array;
        try {
            if (trimmed.startsWith("{")) {
                array = DataArray.empty().add(DataObject.fromJson(trimmed));
            } else {
                array = DataArray.fromJson(trimmed);
            }
        } catch (ParsingException ex) {
            throw new IllegalArgumentException("embedsJson must be a JSON array of embed objects: " + ex.getMessage(), ex);
        }

        if (array.length() > Message.MAX_EMBED_COUNT) {
            throw new IllegalArgumentException("embedsJson cannot contain more than " + Message.MAX_EMBED_COUNT + " embeds");
        }

        List<MessageEmbed> embeds = new ArrayList<>(array.length());
        for (int i = 0; i < array.length(); i++) {
            String prefix = "embedsJson[" + i + "]";
            if (array.isNull(i) || !array.isType(i, DataType.OBJECT)) {
                throw new IllegalArgumentException(prefix + " must be an embed object");
            }
            embeds.add(parseEmbed(array.getObject(i), prefix));
        }
        return List.copyOf(embeds);
    }

    private static MessageEmbed parseEmbed(DataObject obj, String prefix) {
        EmbedBuilder builder = new EmbedBuilder();

        String title = optionalString(obj, "title", prefix);
        String titleUrl = firstNonBlank(
                optionalString(obj, "titleUrl", prefix),
                optionalString(obj, "url", prefix)
        );
        if (title != null) {
            requireMaxLength(title, MessageEmbed.TITLE_MAX_LENGTH, prefix + ".title");
            try {
                builder.setTitle(title, titleUrl);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(prefix + " title/url is invalid: " + ex.getMessage(), ex);
            }
        } else if (titleUrl != null) {
            try {
                builder.setUrl(titleUrl);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(prefix + ".url is invalid: " + ex.getMessage(), ex);
            }
        }

        String description = optionalString(obj, "description", prefix);
        if (description != null) {
            requireMaxLength(description, MessageEmbed.DESCRIPTION_MAX_LENGTH, prefix + ".description");
            builder.setDescription(description);
        }

        if (hasValue(obj, "color")) {
            builder.setColor(parseColor(obj, prefix));
        }

        if (hasValue(obj, "footer")) {
            applyFooter(builder, obj, prefix);
        }
        if (hasValue(obj, "author")) {
            if (!obj.isType("author", DataType.OBJECT)) {
                throw new IllegalArgumentException(prefix + ".author must be { name, url?, iconUrl? }");
            }
            applyAuthor(builder, obj.getObject("author"), prefix);
        }
        if (hasValue(obj, "fields")) {
            if (!obj.isType("fields", DataType.ARRAY)) {
                throw new IllegalArgumentException(prefix + ".fields must be an array");
            }
            applyFields(builder, obj.getArray("fields"), prefix);
        }

        String thumbnailUrl = firstNonBlank(
                optionalString(obj, "thumbnailUrl", prefix),
                optionalString(obj, "thumbnail", prefix)
        );
        if (thumbnailUrl != null) {
            try {
                builder.setThumbnail(thumbnailUrl);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(prefix + ".thumbnailUrl is invalid: " + ex.getMessage(), ex);
            }
        }

        String imageUrl = firstNonBlank(
                optionalString(obj, "imageUrl", prefix),
                optionalString(obj, "image", prefix)
        );
        if (imageUrl != null) {
            try {
                builder.setImage(imageUrl);
            } catch (IllegalArgumentException ex) {
                throw new IllegalArgumentException(prefix + ".imageUrl is invalid: " + ex.getMessage(), ex);
            }
        }

        String timestamp = optionalString(obj, "timestamp", prefix);
        if (timestamp != null) {
            builder.setTimestamp(parseTimestamp(timestamp, prefix));
        }

        try {
            return builder.build();
        } catch (IllegalStateException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(prefix + " is invalid: " + ex.getMessage(), ex);
        }
    }

    private static void applyFooter(EmbedBuilder builder, DataObject obj, String prefix) {
        if (obj.isType("footer", DataType.STRING)) {
            String text = obj.getString("footer");
            requireMaxLength(text, MessageEmbed.TEXT_MAX_LENGTH, prefix + ".footer");
            builder.setFooter(text);
            return;
        }
        if (!obj.isType("footer", DataType.OBJECT)) {
            throw new IllegalArgumentException(prefix + ".footer must be a string or { text, iconUrl? }");
        }

        DataObject footer = obj.getObject("footer");
        String text = optionalString(footer, "text", prefix + ".footer");
        if (text == null) {
            throw new IllegalArgumentException(prefix + ".footer.text is required");
        }
        requireMaxLength(text, MessageEmbed.TEXT_MAX_LENGTH, prefix + ".footer.text");
        String iconUrl = firstNonBlank(
                optionalString(footer, "iconUrl", prefix + ".footer"),
                optionalString(footer, "icon_url", prefix + ".footer")
        );
        try {
            builder.setFooter(text, iconUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(prefix + ".footer is invalid: " + ex.getMessage(), ex);
        }
    }

    private static void applyAuthor(EmbedBuilder builder, DataObject author, String prefix) {
        String name = optionalString(author, "name", prefix + ".author");
        if (name == null) {
            throw new IllegalArgumentException(prefix + ".author.name is required");
        }
        requireMaxLength(name, MessageEmbed.AUTHOR_MAX_LENGTH, prefix + ".author.name");
        String url = optionalString(author, "url", prefix + ".author");
        String iconUrl = firstNonBlank(
                optionalString(author, "iconUrl", prefix + ".author"),
                optionalString(author, "icon_url", prefix + ".author")
        );
        try {
            builder.setAuthor(name, url, iconUrl);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(prefix + ".author is invalid: " + ex.getMessage(), ex);
        }
    }

    private static void applyFields(EmbedBuilder builder, DataArray fields, String prefix) {
        if (fields.length() > MessageEmbed.MAX_FIELD_AMOUNT) {
            throw new IllegalArgumentException(prefix + ".fields cannot contain more than "
                    + MessageEmbed.MAX_FIELD_AMOUNT + " fields");
        }
        for (int i = 0; i < fields.length(); i++) {
            String fieldPrefix = prefix + ".fields[" + i + "]";
            if (fields.isNull(i) || !fields.isType(i, DataType.OBJECT)) {
                throw new IllegalArgumentException(fieldPrefix + " must be an object");
            }
            DataObject field = fields.getObject(i);
            String name = optionalString(field, "name", fieldPrefix);
            String value = optionalString(field, "value", fieldPrefix);
            if (name == null) {
                throw new IllegalArgumentException(fieldPrefix + ".name is required");
            }
            if (value == null) {
                throw new IllegalArgumentException(fieldPrefix + ".value is required");
            }
            requireMaxLength(name, MessageEmbed.TITLE_MAX_LENGTH, fieldPrefix + ".name");
            requireMaxLength(value, MessageEmbed.VALUE_MAX_LENGTH, fieldPrefix + ".value");
            builder.addField(name, value, optionalBoolean(field, "inline", fieldPrefix));
        }
    }

    private static int parseColor(DataObject obj, String prefix) {
        if (obj.isType("color", DataType.INT) || obj.isType("color", DataType.FLOAT)) {
            int value = obj.getInt("color");
            if (value < 0 || value > 0xFFFFFF) {
                throw new IllegalArgumentException(prefix + ".color must be an RGB integer 0-16777215 or hex string like #5865F2");
            }
            return value;
        }
        if (obj.isType("color", DataType.STRING)) {
            return parseColorString(obj.getString("color"), prefix);
        }
        throw new IllegalArgumentException(prefix + ".color must be an RGB integer 0-16777215 or hex string like #5865F2");
    }

    private static int parseColorString(String raw, String prefix) {
        String hex = raw.trim();
        if (hex.startsWith("#")) {
            hex = hex.substring(1);
        } else if (hex.regionMatches(true, 0, "0x", 0, 2)) {
            hex = hex.substring(2);
        }
        try {
            int value = Integer.parseInt(hex, 16);
            if (value < 0 || value > 0xFFFFFF) {
                throw new NumberFormatException("out of range");
            }
            return value;
        } catch (NumberFormatException ex) {
            throw new IllegalArgumentException(prefix + ".color must be an RGB integer 0-16777215 or hex string like #5865F2");
        }
    }

    private static TemporalAccessor parseTimestamp(String raw, String prefix) {
        String value = raw.trim();
        try {
            return OffsetDateTime.parse(value);
        } catch (DateTimeParseException ignored) {
            // try Instant / local date-time next
        }
        try {
            return Instant.parse(value);
        } catch (DateTimeParseException ignored) {
            // try local date-time assumed UTC
        }
        try {
            return LocalDateTime.parse(value).atOffset(ZoneOffset.UTC);
        } catch (DateTimeParseException ex) {
            throw new IllegalArgumentException(prefix + ".timestamp must be ISO-8601 (e.g. 2026-09-23T12:00:00Z)");
        }
    }

    private static boolean optionalBoolean(DataObject obj, String key, String prefix) {
        if (!hasValue(obj, key)) {
            return false;
        }
        if (obj.isType(key, DataType.BOOLEAN)) {
            return obj.getBoolean(key);
        }
        if (obj.isType(key, DataType.STRING)) {
            return Boolean.parseBoolean(obj.getString(key));
        }
        throw new IllegalArgumentException(prefix + "." + key + " must be a boolean");
    }

    private static String optionalString(DataObject obj, String key, String prefix) {
        if (!hasValue(obj, key)) {
            return null;
        }
        if (!obj.isType(key, DataType.STRING)) {
            throw new IllegalArgumentException(prefix + "." + key + " must be a string");
        }
        String value = obj.getString(key);
        return value.isBlank() ? null : value;
    }

    private static boolean hasValue(DataObject obj, String key) {
        return obj.hasKey(key) && !obj.isNull(key);
    }

    private static void requireMaxLength(String value, int max, String fieldName) {
        if (value.length() > max) {
            throw new IllegalArgumentException(fieldName + " exceeds " + max + " characters");
        }
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return null;
    }
}
