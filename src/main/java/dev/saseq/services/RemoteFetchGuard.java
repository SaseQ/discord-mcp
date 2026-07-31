package dev.saseq.services;

import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.URI;
import java.net.URLConnection;
import java.net.UnknownHostException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Shared guard for fetching caller-supplied URLs.
 *
 * <p>Any tool that downloads from a URL the caller controls is an SSRF vector: an MCP client can
 * ask the server to fetch {@code http://169.254.169.254/} or an address on the host's private
 * network, and the response comes back through the tool result. This centralises the checks so a
 * new download site cannot silently ship without them, which is exactly what happened when
 * {@code send_file} was added after {@code createEmoji} had already been hardened.
 *
 * <p>Enforces: https only, a public destination, no redirect following (a 30x could bounce to a
 * blocked address after the check passed), a 200 response, a bounded read, and finite timeouts.
 */
public final class RemoteFetchGuard {

    private static final int CONNECT_TIMEOUT_MS = 5000;
    private static final int READ_TIMEOUT_MS = 10000;

    /**
     * Blocked destinations, as {prefix bytes, prefix length in bits}.
     *
     * <p>Deliberately an explicit CIDR list rather than the {@link InetAddress} predicates. Those
     * predicates leave real gaps: {@code isSiteLocalAddress()} does not cover carrier-grade NAT
     * ({@code 100.64/10}), {@code 0.0.0.0/8}, IETF protocol assignments ({@code 192.0.0.0/24},
     * which includes DS-Lite), benchmarking space ({@code 198.18/15}), reserved
     * {@code 240.0.0.0/4}, or the broadcast address; and nothing in the predicate set touches
     * NAT64 ({@code 64:ff9b::/96}), where {@code 64:ff9b::a00:1} reaches {@code 10.0.0.1}.
     */
    private static final int[][] BLOCKED_V4 = {
            {0, 0, 0, 0, 8},          // "this host on this network"
            {10, 0, 0, 0, 8},         // RFC1918
            {100, 64, 0, 0, 10},      // carrier-grade NAT
            {127, 0, 0, 0, 8},        // loopback
            {169, 254, 0, 0, 16},     // link-local, incl. cloud metadata
            {172, 16, 0, 0, 12},      // RFC1918
            {192, 0, 0, 0, 24},       // IETF protocol assignments, incl. DS-Lite
            {192, 0, 2, 0, 24},       // TEST-NET-1
            {192, 168, 0, 0, 16},     // RFC1918
            {198, 18, 0, 0, 15},      // benchmarking
            {198, 51, 100, 0, 24},    // TEST-NET-2
            {203, 0, 113, 0, 24},     // TEST-NET-3
            {224, 0, 0, 0, 4},        // multicast
            {240, 0, 0, 0, 4},        // reserved, incl. 255.255.255.255
    };

    private RemoteFetchGuard() {
    }

    /**
     * The body was larger than the caller allowed.
     *
     * <p>Distinguishable from every other failure on purpose. A caller running a byte budget
     * across several fetches needs to tell "this response was too big, and reading it spent the
     * allowance" from "the host was unreachable, and it spent nothing" — the two have opposite
     * consequences for what is left to spend. String-matching the message would work and would
     * break the first time anyone reworded it.
     */
    public static class TooLargeException extends IllegalArgumentException {
        TooLargeException(String message) {
            super(message);
        }
    }

    /**
     * The transfer failed partway, after {@link #bytesConsumed()} bytes had already arrived.
     *
     * <p>A response that dies at 44 MB cost 44 MB of bandwidth, and a caller running a byte
     * budget has to charge it — otherwise repeated mid-transfer failures pull far more than the
     * budget allows while the counter never moves. The count is carried on the exception because
     * only the read loop knows it.
     *
     * <p>The message stays deliberately generic for the same reason the plain failure path does:
     * echoing the underlying {@link IOException} would turn this into a reachability prober.
     */
    public static class TransferFailedException extends IllegalArgumentException {
        private final int bytesConsumed;

        TransferFailedException(String message, int bytesConsumed) {
            super(message);
            this.bytesConsumed = bytesConsumed;
        }

        public int bytesConsumed() {
            return bytesConsumed;
        }
    }

    /**
     * Fetch a caller-supplied URL with SSRF protection and a size ceiling.
     *
     * @param url      the caller-supplied URL
     * @param maxBytes reject responses larger than this
     * @param what     noun used in error messages, e.g. "image" or "file"
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
                if (conn instanceof HttpURLConnection httpConn) {
                    int status = httpConn.getResponseCode();
                    if (status != HttpURLConnection.HTTP_OK) {
                        // With redirects disabled, a 3xx body would otherwise be
                        // uploaded as if it were the requested content.
                        throw new IllegalArgumentException(
                                "Refusing " + what + " URL: server returned HTTP " + status);
                    }
                }
                return readBounded(in, maxBytes, what);
            }
        } catch (IOException e) {
            // Deliberately does not echo the underlying IOException. Passing it
            // through turns this into a reachability prober for arbitrary
            // public IP:port, including the host's own public address.
            throw new IllegalArgumentException("Failed to download " + what + " from URL");
        }
    }

    /**
     * Reads at most {@code maxBytes}, reporting how much arrived if the transfer fails.
     *
     * <p>Hand-rolled rather than {@code readNBytes(maxBytes + 1)} for one reason: {@code
     * readNBytes} discards its partial buffer when the stream errors, so a caller cannot tell a
     * failure that cost nothing from one that cost 44 MB. Both look identical, and a byte budget
     * built on that distinction silently stops bounding anything.
     */
    private static byte[] readBounded(InputStream in, int maxBytes, String what) {
        // Chunks in a list, assembled once — not a ByteArrayOutputStream. BAOS doubles its
        // internal array as it grows and then toByteArray() copies again, so peak heap reaches
        // roughly three times the body. This holds the data once plus the final array, matching
        // what readNBytes did before, which matters because these limits are 50 MB and the
        // deployment this was written for runs the JVM at a few hundred megabytes.
        List<byte[]> chunks = new ArrayList<>();
        int total = 0;
        while (true) {
            byte[] chunk = new byte[CHUNK_BYTES];
            int read;
            try {
                read = in.read(chunk);
            } catch (IOException e) {
                throw new TransferFailedException("Failed to download " + what + " from URL", total);
            }
            if (read < 0) {
                break;
            }
            total += read;
            if (total > maxBytes) {
                throw new TooLargeException(what + " exceeds the maximum allowed size");
            }
            chunks.add(read == CHUNK_BYTES ? chunk : Arrays.copyOf(chunk, read));
        }

        byte[] body = new byte[total];
        int offset = 0;
        for (byte[] chunk : chunks) {
            System.arraycopy(chunk, 0, body, offset, chunk.length);
            offset += chunk.length;
        }
        return body;
    }

    private static final int CHUNK_BYTES = 8192;

    private static void assertHostIsPublic(String host, String what) {
        InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException e) {
            throw new IllegalArgumentException("Cannot resolve " + what + " host: " + host);
        }
        if (addresses.length == 0) {
            throw new IllegalArgumentException("Cannot resolve " + what + " host: " + host);
        }
        // Reject if ANY answer is blocked, not just the one that would be used.
        // A multi-record answer must not be able to smuggle a private address.
        for (InetAddress addr : addresses) {
            if (isBlocked(addr)) {
                throw new IllegalArgumentException(
                        what + " URL resolves to a disallowed (internal) address");
            }
        }
    }

    static boolean isBlocked(InetAddress addr) {
        byte[] bytes = canonicalize(addr.getAddress());

        if (bytes.length == 4) {
            for (int[] rule : BLOCKED_V4) {
                byte[] prefix = {(byte) rule[0], (byte) rule[1], (byte) rule[2], (byte) rule[3]};
                if (matchesPrefix(bytes, prefix, rule[4])) {
                    return true;
                }
            }
            return false;
        }

        if (bytes.length == 16) {
            if (addr.isLoopbackAddress() || addr.isAnyLocalAddress()
                    || addr.isLinkLocalAddress() || addr.isSiteLocalAddress()
                    || addr.isMulticastAddress()) {
                return true;
            }
            // fc00::/7 unique-local is not covered by isSiteLocalAddress().
            if ((bytes[0] & 0xFE) == 0xFC) {
                return true;
            }
            // NAT64 well-known prefix 64:ff9b::/96 tunnels IPv4 destinations, so
            // check the embedded v4 address rather than the wrapper.
            if (isNat64(bytes)) {
                byte[] embedded = {bytes[12], bytes[13], bytes[14], bytes[15]};
                for (int[] rule : BLOCKED_V4) {
                    byte[] prefix = {(byte) rule[0], (byte) rule[1], (byte) rule[2], (byte) rule[3]};
                    if (matchesPrefix(embedded, prefix, rule[4])) {
                        return true;
                    }
                }
            }
            return false;
        }

        // Unknown address family: fail closed.
        return true;
    }

    /**
     * Reduce an IPv4-mapped IPv6 address (::ffff:0:0/96) to its four IPv4 bytes.
     *
     * <p>Without this, a mapped address arriving as an {@code Inet6Address} passes every
     * predicate: {@code ::ffff:127.0.0.1} reports neither loopback nor site-local, yet
     * {@code connect()} reaches 127.0.0.1.
     */
    private static byte[] canonicalize(byte[] bytes) {
        if (bytes.length != 16) {
            return bytes;
        }
        for (int i = 0; i < 10; i++) {
            if (bytes[i] != 0) {
                return bytes;
            }
        }
        if ((bytes[10] & 0xFF) == 0xFF && (bytes[11] & 0xFF) == 0xFF) {
            return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
        }
        return bytes;
    }

    /** 64:ff9b::/96 */
    private static boolean isNat64(byte[] b) {
        return (b[0] & 0xFF) == 0x00 && (b[1] & 0xFF) == 0x64
                && (b[2] & 0xFF) == 0xFF && (b[3] & 0xFF) == 0x9B
                && b[4] == 0 && b[5] == 0 && b[6] == 0 && b[7] == 0
                && b[8] == 0 && b[9] == 0 && b[10] == 0 && b[11] == 0;
    }

    private static boolean matchesPrefix(byte[] addr, byte[] prefix, int prefixBits) {
        int fullBytes = prefixBits / 8;
        int remainingBits = prefixBits % 8;
        for (int i = 0; i < fullBytes; i++) {
            if (addr[i] != prefix[i]) {
                return false;
            }
        }
        if (remainingBits == 0) {
            return true;
        }
        int mask = 0xFF << (8 - remainingBits);
        return (addr[fullBytes] & mask) == (prefix[fullBytes] & mask);
    }
}
