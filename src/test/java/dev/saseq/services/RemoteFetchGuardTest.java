package dev.saseq.services;

import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.UnknownHostException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RemoteFetchGuardTest {

    @Test
    void blocksPrivateAndSpecialUseAddresses() {
        assertThat(blocked("0.0.0.0")).isTrue();
        assertThat(blocked("10.0.0.1")).isTrue();
        assertThat(blocked("127.0.0.1")).isTrue();
        assertThat(blocked("172.16.0.1")).isTrue();
        assertThat(blocked("192.168.1.1")).isTrue();
        assertThat(blocked("224.0.0.1")).isTrue();
        assertThat(blocked("255.255.255.255")).isTrue();
        // Cloud instance metadata, the payload that makes SSRF worth exploiting.
        assertThat(blocked("169.254.169.254")).isTrue();
    }

    @Test
    void blocksRangesTheInetAddressPredicatesMiss() {
        assertThat(blocked("100.64.0.1")).isTrue();   // carrier-grade NAT
        assertThat(blocked("192.0.0.1")).isTrue();    // IETF protocol assignments
        assertThat(blocked("198.18.0.1")).isTrue();   // benchmarking
        assertThat(blocked("240.0.0.1")).isTrue();    // reserved
    }

    @Test
    void allowsPublicAddressesJustOutsideTheBlockedPrefixes() {
        assertThat(blocked("8.8.8.8")).isFalse();
        assertThat(blocked("172.32.0.1")).isFalse();   // one past 172.16/12
        assertThat(blocked("100.128.0.1")).isFalse();  // one past 100.64/10
        assertThat(blocked("198.20.0.1")).isFalse();   // one past 198.18/15
    }

    @Test
    void blocksIpv6LoopbackAndUniqueLocal() {
        assertThat(blocked("::1")).isTrue();
        assertThat(blocked("fd00::1")).isTrue();  // fc00::/7, not covered by isSiteLocalAddress
        assertThat(blocked("fe80::1")).isTrue();
        assertThat(blocked("2606:4700::1")).isFalse();
    }

    @Test
    void blocksNat64WrappersAroundPrivateIpv4() {
        // 64:ff9b::/96 tunnels an IPv4 destination, so the embedded address is
        // what connect() actually reaches.
        assertThat(blocked("64:ff9b::7f00:1")).isTrue();      // 127.0.0.1
        assertThat(blocked("64:ff9b::a9fe:a9fe")).isTrue();   // 169.254.169.254
        assertThat(blocked("64:ff9b::808:808")).isFalse();    // 8.8.8.8
    }

    @Test
    void rejectsNonHttpsSchemes() {
        assertThatThrownBy(() -> RemoteFetchGuard.fetch("http://example.com/x.png", 1024, "image"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
        assertThatThrownBy(() -> RemoteFetchGuard.fetch("file:///etc/shadow", 1024, "file"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("https");
    }

    @Test
    void readingStopsOneByteAfterTheAllowanceRatherThanOneChunk() {
        // Asking for a full chunk regardless of remaining allowance let up to maxBytes + 8191
        // bytes reach the loop before it noticed, while the caller charges exactly the allowance
        // for the rejection.
        CountingStream stream = new CountingStream(new byte[64 * 1024]);

        assertThatThrownBy(() -> RemoteFetchGuard.readBounded(stream, 1000, "attachment"))
                .isInstanceOf(RemoteFetchGuard.TooLargeException.class);

        assertThat(stream.delivered).isEqualTo(1001);
    }

    @Test
    void aBodyExactlyAtTheLimitIsReturnedWhole() {
        // The boundary the +1 is most likely to get wrong in a future edit. Off the other way and
        // a 50 MB attachment silently loses its last byte with every other test still green.
        byte[] body = body(1000);

        assertThat(RemoteFetchGuard.readBounded(new CountingStream(body), 1000, "attachment"))
                .isEqualTo(body);
    }

    @Test
    void aMultiChunkBodyReassemblesByteForByte() {
        // Spans several 8 KiB chunks with a partial one at the end, so both the full-chunk and
        // the trimmed-chunk branch of the assembly run.
        byte[] body = body(20_000);

        assertThat(RemoteFetchGuard.readBounded(new CountingStream(body), 50_000, "attachment"))
                .isEqualTo(body);
    }

    @Test
    void anUnlimitedAllowanceReadsNormallyRatherThanFailingOnTheAllocation() {
        // Integer.MAX_VALUE is what a caller reaches for to mean "no limit". Without the long
        // cast, maxBytes - total + 1 wraps and the allocation fails with
        // NegativeArraySizeException on the first iteration.
        //
        // Proves the allocation only. The accumulator guard cannot be exercised without a 2 GB
        // body, so it is written to be overflow-free rather than covered by a test.
        byte[] body = body(9000);

        assertThat(RemoteFetchGuard.readBounded(new CountingStream(body), Integer.MAX_VALUE, "attachment"))
                .isEqualTo(body);
    }

    @Test
    void theSizeBoundFiresOnAPartialFinalChunk() {
        // A boundary test, not an overflow one — at this magnitude nothing can wrap, so it
        // passes against the old arithmetic too. What it pins is that the bound still fires
        // when the overrun arrives in a narrowed final chunk rather than a full one.
        byte[] body = body(12_000);

        assertThatThrownBy(() -> RemoteFetchGuard.readBounded(new CountingStream(body), 11_999, "attachment"))
                .isInstanceOf(RemoteFetchGuard.TooLargeException.class);
    }

    /** Parses a literal address without touching DNS. */
    private boolean blocked(String literal) {
        try {
            return RemoteFetchGuard.isBlocked(InetAddress.getByName(literal));
        } catch (UnknownHostException e) {
            throw new AssertionError("not a literal address: " + literal, e);
        }
    }

    private static byte[] body(int length) {
        byte[] data = new byte[length];
        for (int i = 0; i < length; i++) {
            // Position-dependent, so a misassembled or truncated result cannot match by accident.
            data[i] = (byte) (i % 251);
        }
        return data;
    }

    /** Counts what was actually handed to the reader, which is the thing under test. */
    private static final class CountingStream extends InputStream {
        private final byte[] data;
        private int position;
        private int delivered;

        CountingStream(byte[] data) {
            this.data = data;
        }

        @Override
        public int read() {
            if (position >= data.length) {
                return -1;
            }
            delivered++;
            return data[position++] & 0xFF;
        }

        @Override
        public int read(byte[] target, int offset, int length) {
            // InputStream specifies 0 for a zero-length request, even at EOF. Not reachable
            // through readBounded, which always asks for at least one byte.
            if (length == 0) {
                return 0;
            }
            if (position >= data.length) {
                return -1;
            }
            int count = Math.min(length, data.length - position);
            System.arraycopy(data, position, target, offset, count);
            position += count;
            delivered += count;
            return count;
        }
    }
}
