package dev.saseq.services;

import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.utils.FileUpload;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataType;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Parses MCP attachment path / JSON payloads into JDA {@link FileUpload}s.
 */
final class AttachmentParser {

    static final int MAX_ATTACHMENTS = 10;
    static final long MAX_ATTACHMENT_BYTES = 25L * 1024 * 1024;

    private static final Map<String, String> MIME_EXTENSIONS = Map.ofEntries(
            Map.entry("image/png", "png"),
            Map.entry("image/jpeg", "jpg"),
            Map.entry("image/jpg", "jpg"),
            Map.entry("image/gif", "gif"),
            Map.entry("image/webp", "webp"),
            Map.entry("image/bmp", "bmp"),
            Map.entry("application/pdf", "pdf"),
            Map.entry("text/plain", "txt")
    );

    private AttachmentParser() {
    }

    static List<FileUpload> parse(String attachmentPaths, String attachmentsJson) {
        List<FileUpload> uploads = new ArrayList<>();
        try {
            addFromPaths(uploads, attachmentPaths);
            addFromJson(uploads, attachmentsJson);
            if (uploads.size() > MAX_ATTACHMENTS) {
                throw new IllegalArgumentException("Cannot attach more than " + MAX_ATTACHMENTS
                        + " files per message (Discord limit)");
            }
            return uploads;
        } catch (RuntimeException ex) {
            closeQuietly(uploads);
            throw ex;
        }
    }

    static void closeQuietly(Collection<FileUpload> uploads) {
        if (uploads == null) {
            return;
        }
        for (FileUpload upload : uploads) {
            if (upload == null) {
                continue;
            }
            try {
                upload.close();
            } catch (IOException ignored) {
                // best-effort cleanup after send/edit or a parse failure
            }
        }
    }

    private static void addFromPaths(List<FileUpload> uploads, String attachmentPaths) {
        if (attachmentPaths == null || attachmentPaths.isBlank()) {
            return;
        }

        String trimmed = attachmentPaths.trim();
        if (trimmed.startsWith("[")) {
            DataArray array = parseArray(trimmed, "attachmentPaths");
            for (int i = 0; i < array.length(); i++) {
                String prefix = "attachmentPaths[" + i + "]";
                if (array.isNull(i) || !array.isType(i, DataType.STRING)) {
                    throw new IllegalArgumentException(prefix + " must be an absolute file path string");
                }
                uploads.add(fromPath(array.getString(i), null, prefix));
            }
            return;
        }

        uploads.add(fromPath(trimmed, null, "attachmentPaths"));
    }

    private static void addFromJson(List<FileUpload> uploads, String attachmentsJson) {
        if (attachmentsJson == null || attachmentsJson.isBlank()) {
            return;
        }

        String trimmed = attachmentsJson.trim();
        DataArray array;
        if (trimmed.startsWith("{") || trimmed.regionMatches(true, 0, "data:", 0, 5)) {
            array = DataArray.empty();
            if (trimmed.startsWith("{")) {
                try {
                    array.add(DataObject.fromJson(trimmed));
                } catch (ParsingException ex) {
                    throw new IllegalArgumentException("attachmentsJson must be a JSON array of attachment objects: " + ex.getMessage(), ex);
                }
            } else {
                array.add(trimmed);
            }
        } else {
            array = parseArray(trimmed, "attachmentsJson");
        }

        for (int i = 0; i < array.length(); i++) {
            uploads.add(parseJsonItem(array, i));
        }
    }

    private static FileUpload parseJsonItem(DataArray array, int index) {
        String prefix = "attachmentsJson[" + index + "]";
        if (array.isNull(index)) {
            throw new IllegalArgumentException(prefix + " cannot be null");
        }
        if (array.isType(index, DataType.STRING)) {
            String value = array.getString(index);
            if (value.regionMatches(true, 0, "data:", 0, 5)) {
                return fromBase64(null, value, prefix);
            }
            return fromPath(value, null, prefix);
        }
        if (!array.isType(index, DataType.OBJECT)) {
            throw new IllegalArgumentException(prefix + " must be a path string, data-URI, { path }, or { filename, base64 }");
        }

        DataObject obj = array.getObject(index);
        String filename = firstNonBlank(
                optionalString(obj, "filename", prefix),
                optionalString(obj, "name", prefix)
        );
        String path = optionalString(obj, "path", prefix);
        String base64 = firstNonBlank(
                optionalString(obj, "base64", prefix),
                optionalString(obj, "dataUri", prefix),
                optionalString(obj, "dataURI", prefix)
        );

        if (path != null) {
            return fromPath(path, filename, prefix);
        }
        if (base64 != null) {
            return fromBase64(filename, base64, prefix);
        }
        throw new IllegalArgumentException(prefix + " must include path or base64/data-URI data");
    }

    private static FileUpload fromPath(String pathValue, String filenameOverride, String prefix) {
        if (pathValue == null || pathValue.isBlank()) {
            throw new IllegalArgumentException(prefix + " path cannot be blank");
        }

        Path path = Path.of(pathValue.trim());
        if (!path.isAbsolute()) {
            throw new IllegalArgumentException(prefix + " must be an absolute file path: " + pathValue);
        }
        if (!Files.exists(path)) {
            throw new IllegalArgumentException(prefix + " file not found: " + path);
        }
        if (!Files.isRegularFile(path)) {
            throw new IllegalArgumentException(prefix + " is not a regular file: " + path);
        }
        if (!Files.isReadable(path)) {
            throw new IllegalArgumentException(prefix + " is not readable: " + path);
        }

        long size;
        try {
            size = Files.size(path);
        } catch (IOException ex) {
            throw new IllegalArgumentException(prefix + " could not read file size: " + path, ex);
        }
        if (size > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException(prefix + " exceeds the 25 MB upload limit: " + path
                    + " (" + size + " bytes)");
        }

        String name = filenameOverride != null && !filenameOverride.isBlank()
                ? filenameOverride
                : (path.getFileName() != null ? path.getFileName().toString() : "");
        if (name.isBlank()) {
            throw new IllegalArgumentException(prefix + " does not have a file name: " + path);
        }

        try {
            return FileUpload.fromData(path, name);
        } catch (UncheckedIOException | IllegalArgumentException ex) {
            throw new IllegalArgumentException(prefix + " could not open file: " + path + " (" + ex.getMessage() + ")", ex);
        }
    }

    private static FileUpload fromBase64(String filename, String raw, String prefix) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException(prefix + " base64 data cannot be blank");
        }

        String data = raw.trim();
        String resolvedName = filename;
        if (data.regionMatches(true, 0, "data:", 0, 5)) {
            int comma = data.indexOf(',');
            if (comma < 0) {
                throw new IllegalArgumentException(prefix + " data-URI is missing payload data");
            }
            String header = data.substring(5, comma);
            String payload = data.substring(comma + 1);
            String headerLower = header.toLowerCase(Locale.ROOT);
            if (!headerLower.contains("base64")) {
                throw new IllegalArgumentException(prefix + " data-URI must be base64-encoded");
            }
            String mime = header.split(";", 2)[0].trim().toLowerCase(Locale.ROOT);
            if (resolvedName == null || resolvedName.isBlank()) {
                resolvedName = filenameFromMime(mime);
            }
            data = payload;
        }

        if (resolvedName == null || resolvedName.isBlank()) {
            throw new IllegalArgumentException(prefix + " filename is required for base64 attachments");
        }

        byte[] bytes;
        try {
            bytes = Base64.getDecoder().decode(data);
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(prefix + " contains invalid base64 data", ex);
        }
        if (bytes.length == 0) {
            throw new IllegalArgumentException(prefix + " base64 data is empty");
        }
        if (bytes.length > MAX_ATTACHMENT_BYTES) {
            throw new IllegalArgumentException(prefix + " exceeds the 25 MB upload limit (" + bytes.length + " bytes)");
        }
        return FileUpload.fromData(bytes, resolvedName);
    }

    private static String filenameFromMime(String mime) {
        String extension = MIME_EXTENSIONS.getOrDefault(mime, "bin");
        return "attachment." + extension;
    }

    private static DataArray parseArray(String json, String paramName) {
        try {
            return DataArray.fromJson(json);
        } catch (ParsingException ex) {
            throw new IllegalArgumentException(paramName + " must be a JSON array: " + ex.getMessage(), ex);
        }
    }

    private static String optionalString(DataObject obj, String key, String prefix) {
        if (!obj.hasKey(key) || obj.isNull(key)) {
            return null;
        }
        if (!obj.isType(key, DataType.STRING)) {
            throw new IllegalArgumentException(prefix + "." + key + " must be a string");
        }
        String value = obj.getString(key);
        return value.isBlank() ? null : value;
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
