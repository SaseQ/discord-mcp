package dev.saseq.services;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Confine caller-supplied local file reads to an allowlisted root.
 *
 * <p>Shared code on purpose, and the counterpart to {@link RemoteFetchGuard}. Every tool here is
 * reachable by a model, so a tool that opens a caller-supplied path will read whatever the process
 * can read. On a host that loads its bot token from the environment, that is one call from posting
 * a credential into a chat channel.
 *
 * <p>This lives in its own class because the repo has already paid for the alternative: the SSRF
 * guard existed as a private method inside one service, and {@code send_file} then shipped with its
 * own unguarded fetch. A private helper protects the file it lives in and nothing else. The second
 * caller — {@code set_guild_scheduled_event_image} — is what prompted the extraction.
 */
public final class LocalFileGuard {

    private LocalFileGuard() {
    }

    /**
     * Resolve a configured root directory, rejecting the shapes that would confine nothing.
     *
     * @param configured   the raw configured value
     * @param variableName the environment variable it came from, for error messages
     * @return the fully resolved real path of the root
     */
    public static Path resolveRoot(String configured, String variableName) {
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

    /**
     * Confine one caller-supplied path to an already-resolved root.
     *
     * <p>The returned path is the one the caller must open. Opening the requested path instead
     * would defeat the check entirely: the whole point is that the two can differ.
     *
     * @param filePath  the caller-supplied path
     * @param allowed   the resolved root, from {@link #resolveRoot}
     * @param paramName the tool parameter the path came from, for error messages
     * @param rootName  what the root is for ("upload", "cover image"), for error messages. A
     *                  caller that reads several kinds of file from several roots gets a message
     *                  that says which one it was refused from, rather than a generic one that
     *                  leaves the operator guessing which grant to widen.
     * @return the fully resolved real path
     */
    public static Path resolveWithinRoot(String filePath, Path allowed, String paramName, String rootName) {
        Path real;
        try {
            // toRealPath, not normalize: normalize is purely lexical, so a symlink
            // inside the root pointing at /etc/shadow passes a prefix check on the
            // normalized path. Both sides must be resolved for the comparison to mean
            // anything, and the resolved path is what gets opened.
            real = Paths.get(filePath).toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("File not found at " + paramName + ": " + filePath);
        }
        if (!real.startsWith(allowed) || real.equals(allowed)) {
            throw new IllegalArgumentException(
                    paramName + " is outside the allowed " + rootName + " directory");
        }
        if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(paramName + " is not a regular file: " + filePath);
        }
        return real;
    }

    /**
     * Read a resolved path, refusing anything over the limit.
     *
     * <p>Reads one byte past the limit rather than consulting the file's size: a size check
     * followed by a full read is a time-of-check/time-of-use gap, and {@code readAllBytes} on a
     * path the caller chose would exhaust the heap long before any check could reject it. This
     * process runs with a 320 MB heap.
     *
     * @param real     a path already resolved by {@link #resolveWithinRoot}
     * @param maxBytes the largest body to accept
     * @param what     what the file is, for the error message
     */
    public static byte[] readBounded(Path real, int maxBytes, String what) {
        byte[] bytes;
        try (InputStream in = Files.newInputStream(real, LinkOption.NOFOLLOW_LINKS)) {
            bytes = in.readNBytes(maxBytes + 1);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to read " + what + ": " + e.getMessage());
        }
        if (bytes.length > maxBytes) {
            throw new IllegalArgumentException(
                    what + " exceeds the " + (maxBytes / (1024 * 1024)) + " MB limit.");
        }
        return bytes;
    }
}
