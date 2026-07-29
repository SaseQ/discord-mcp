package dev.saseq.services;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLConnection;
import java.net.UnknownHostException;

/**
 * Shared guard for fetching caller-supplied URLs.
 *
 * <p>Any tool that downloads from a URL the caller controls is an SSRF vector: an MCP client can
 * ask the server to fetch {@code http://169.254.169.254/} or an address on the host's private
 * network, and the response comes back through the tool result. This centralises the checks so a
 * new download site cannot silently ship without them, which is exactly what happened when
 * {@code send_file} was added after {@code createEmoji} had already been hardened.
 *
 * <p>Enforces: https only, resolvable public host, no redirect following (a 30x could bounce to a
 * blocked address after the check passed), bounded read, and finite timeouts.
 */
public final class RemoteFetchGuard {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    private RemoteFetchGuard() {
    }

    /**
     * Fetch a caller-supplied URL with SSRF protection and a size ceiling.
     *
     * @param url        the caller-supplied URL
     * @param maxBytes   reject responses larger than this
     * @param what       noun used in error messages, e.g. "image" or "file"
     * @return the response body
     */
    public static byte[] fetch(String url, int maxBytes, String what) {
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid " + what + " URL: " + e.getMessage());
        }

        String scheme = uri.getScheme();
        if (scheme == null || !scheme.equalsIgnoreCase("https")) {
            throw new IllegalArgumentException(what + " URL must use the https scheme");
        }
        String host = uri.getHost();
        if (host == null || host.isEmpty()) {
            throw new IllegalArgumentException(what + " URL must include a host");
        }
        assertHostIsPublic(host, what);

        try {
            URLConnection conn = uri.toURL().openConnection();
            conn.setConnectTimeout(CONNECT_TIMEOUT_MS);
            conn.setReadTimeout(READ_TIMEOUT_MS);
            if (conn instanceof HttpURLConnection httpConn) {
                // A 30x could bounce to a blocked address after the host check passed.
                httpConn.setInstanceFollowRedirects(false);
            }
            try (InputStream in = conn.getInputStream()) {
                byte[] data = in.readNBytes(maxBytes + 1);
                if (data.length > maxBytes) {
                    throw new IllegalArgumentException(what + " exceeds the maximum allowed size");
                }
                return data;
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to download " + what + " from URL: " + e.getMessage());
        }
    }

    private static void assertHostIsPublic(String host, String what) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve " + what + " host: " + host);
        }
        for (InetAddress addr : addresses) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isMulticastAddress() || isUniqueLocalIpv6(addr)) {
                throw new IllegalArgumentException(
                        what + " URL resolves to a disallowed (internal) address");
            }
        }
    }

    /** IPv6 unique-local range fc00::/7 is not covered by isSiteLocalAddress(). */
    private static boolean isUniqueLocalIpv6(InetAddress addr) {
        byte[] bytes = addr.getAddress();
        return bytes.length == 16 && (bytes[0] & 0xFE) == 0xFC;
    }
}
