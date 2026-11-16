package dev.saseq.services;

import com.sun.net.httpserver.HttpServer;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.*;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.*;

class RemoteFetchGuardTest {
    @ParameterizedTest
    @ValueSource(strings = {"http://example.com/a", "file:///tmp/a", "https://u:p@example.com/a",
            "https://127.0.0.1/a", "https://[::1]/a", "https://2130706433/a"})
    void rejectsUnsafeUrls(String url) {
        assertThatThrownBy(() -> RemoteFetchGuard.fetch(url, 10, "image"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {"0.1.2.3", "10.0.0.1", "100.64.0.1", "127.1.2.3", "169.254.169.254",
            "172.16.0.1", "192.168.1.1", "192.0.2.1", "198.18.0.1", "198.51.100.1", "203.0.113.1",
            "224.0.0.1", "240.1.1.1", "::", "::1", "fc00::1", "fe80::1", "ff02::1",
            "::ffff:127.0.0.1", "64:ff9b::a00:1", "2001:db8::1", "2002:7f00:1::", "3fff::1"})
    void blocksSpecialUseAddresses(String ip) throws Exception {
        assertThat(RemoteFetchGuard.blocked(InetAddress.getByName(ip))).isTrue();
    }

    @Test
    void dnsReturnsTheValidatedAddressesAndRejectsMixedAnswers() throws Exception {
        InetAddress publicIp = InetAddress.getByName("93.184.216.34");
        assertThat(RemoteFetchGuard.client(host -> List.of(publicIp)).dns().lookup("media.example"))
                .containsExactly(publicIp);
        var client = RemoteFetchGuard.client(host -> List.of(publicIp, InetAddress.getLoopbackAddress()));
        assertThatThrownBy(() -> client.dns().lookup("media.example")).isInstanceOf(UnknownHostException.class);
        assertThat(RemoteFetchGuard.blocked(InetAddress.getByName("2606:4700:4700::1111"))).isFalse();
    }

    @Test
    void connectionUsesValidatedDnsAnswer() throws Exception {
        InetAddress address = InetAddress.getByName("93.184.216.34");
        var client = RemoteFetchGuard.client(host -> List.of(address)).newBuilder()
                .eventListener(new EventListener() {
                    @Override public void connectStart(Call call, InetSocketAddress peer, Proxy proxy) {
                        assertThat(peer.getAddress()).isEqualTo(address);
                        throw new IllegalStateException("stopped before network connection");
                    }
                }).build();
        assertThatThrownBy(() -> RemoteFetchGuard.fetch(client, "https://media.example/a", 10, "image"))
                .hasMessage("stopped before network connection");
    }

    @Test
    void emojiUsesSharedLimitBeforeUpload() {
        var jda = org.mockito.Mockito.mock(net.dv8tion.jda.api.JDA.class);
        var guild = org.mockito.Mockito.mock(net.dv8tion.jda.api.entities.Guild.class);
        org.mockito.Mockito.when(jda.getGuildById("123")).thenReturn(guild);
        try (var guard = org.mockito.Mockito.mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch("https://media.example/a", 262144, "image"))
                    .thenThrow(new IllegalArgumentException("bounded fetch"));
            assertThatThrownBy(() -> new EmojiService(jda).createEmoji("123", "icon", null,
                    "https://media.example/a", null)).hasMessage("bounded fetch");
            guard.verify(() -> RemoteFetchGuard.fetch("https://media.example/a", 262144, "image"));
            org.mockito.Mockito.verifyNoInteractions(guild);
        }
    }

    @Test
    void clientHasBoundedUnauthenticatedPolicy() {
        var client = RemoteFetchGuard.client(Dns.SYSTEM);
        assertThat(client.connectTimeoutMillis()).isEqualTo(5000);
        assertThat(client.readTimeoutMillis()).isEqualTo(10000);
        assertThat(client.callTimeoutMillis()).isEqualTo(15000);
        assertThat(client.followRedirects()).isFalse();
        assertThat(client.followSslRedirects()).isFalse();
        assertThat(client.retryOnConnectionFailure()).isFalse();
        assertThat(client.proxy()).isEqualTo(Proxy.NO_PROXY);
        assertThat(client.cookieJar()).isSameAs(CookieJar.NO_COOKIES);
    }

    @Test
    void boundsBodyWithoutTrustingLengthAndClosesIt() {
        AtomicInteger closed = new AtomicInteger();
        var client = RemoteFetchGuard.client(Dns.SYSTEM).newBuilder().addInterceptor(chain -> {
            assertThat(chain.request().headers().names()).doesNotContain("Authorization", "Cookie");
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1)
                    .code(200).message("OK").body(new ResponseBody() {
                        private final okio.Buffer buffer = new okio.Buffer().write(new byte[262145]);
                        @Override public MediaType contentType() { return null; }
                        @Override public long contentLength() { return -1; }
                        @Override public okio.BufferedSource source() { return buffer; }
                        @Override public void close() { closed.incrementAndGet(); super.close(); }
                    }).build();
        }).build();
        assertThatThrownBy(() -> RemoteFetchGuard.fetch(client, "https://media.example/a", 262144, "image"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size");
        assertThat(closed.get()).isPositive();
        assertThat(RemoteFetchGuard.fetch(client, "https://media.example/a", 262145, "image")).hasSize(262145);
    }

    @Test
    void rejectsRedirectWithoutFollowing() {
        AtomicInteger calls = new AtomicInteger();
        var client = RemoteFetchGuard.client(Dns.SYSTEM).newBuilder().addInterceptor(chain -> {
            calls.incrementAndGet();
            return new Response.Builder().request(chain.request()).protocol(Protocol.HTTP_1_1).code(302)
                    .message("Redirect").header("Location", "https://127.0.0.1/private")
                    .body(ResponseBody.create("", null)).build();
        }).build();
        assertThatThrownBy(() -> RemoteFetchGuard.fetch(client, "https://media.example/a", 10, "image"))
                .hasMessageContaining("HTTP 302");
        assertThat(calls.get()).isEqualTo(1);
    }

    @Test
    void callTimeoutBoundsTrickleAndCompressedBytesAreBounded() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        server.createContext("/slow", exchange -> {
            exchange.sendResponseHeaders(200, 0);
            try (var out = exchange.getResponseBody()) {
                while (true) { out.write(1); out.flush(); Thread.sleep(20); }
            } catch (IOException | InterruptedException ignored) { }
        });
        server.createContext("/gzip", exchange -> {
            var bytes = new java.io.ByteArrayOutputStream();
            try (var gzip = new java.util.zip.GZIPOutputStream(bytes)) { gzip.write(new byte[262145]); }
            exchange.getResponseHeaders().add("Content-Encoding", "gzip");
            exchange.sendResponseHeaders(200, bytes.size());
            try (var out = exchange.getResponseBody()) { out.write(bytes.toByteArray()); }
        });
        server.setExecutor(java.util.concurrent.Executors.newCachedThreadPool(r -> {
            Thread t = new Thread(r); t.setDaemon(true); return t;
        }));
        server.start();
        try {
            // The local fixture substitutes DNS only; production policy is tested above.
            var client = RemoteFetchGuard.client(Dns.SYSTEM).newBuilder()
                    .dns(host -> List.of(InetAddress.getLoopbackAddress()))
                    .callTimeout(500, TimeUnit.MILLISECONDS).build();
            String base = "http://media.example:" + server.getAddress().getPort();
            try (var response = client.newCall(new Request.Builder().url(base + "/slow").build()).execute()) {
                assertThatThrownBy(() -> RemoteFetchGuard.read(response, 262144, "image"))
                        .isInstanceOf(IOException.class);
            }
            try (var response = client.newCall(new Request.Builder().url(base + "/gzip").build()).execute()) {
                assertThatThrownBy(() -> RemoteFetchGuard.read(response, 262144, "image"))
                        .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("size");
            }
        } finally { server.stop(0); }
    }
}
