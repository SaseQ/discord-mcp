package dev.saseq.services;

import okhttp3.Dns;
import okhttp3.HttpUrl;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;

import java.io.IOException;
import java.net.InetAddress;
import java.net.Proxy;
import java.net.UnknownHostException;
import java.util.List;
import java.util.concurrent.TimeUnit;

final class RemoteFetchGuard {
    private static final OkHttpClient CLIENT = client(Dns.SYSTEM);
    private static final int[][] BLOCKED_V4 = {
            {0, 0, 0, 0, 8}, {10, 0, 0, 0, 8}, {100, 64, 0, 0, 10}, {127, 0, 0, 0, 8},
            {169, 254, 0, 0, 16}, {172, 16, 0, 0, 12}, {192, 0, 0, 0, 24}, {192, 0, 2, 0, 24},
            {192, 168, 0, 0, 16}, {198, 18, 0, 0, 15}, {198, 51, 100, 0, 24},
            {203, 0, 113, 0, 24}, {224, 0, 0, 0, 4}, {240, 0, 0, 0, 4}
    };

    private RemoteFetchGuard() { }

    static OkHttpClient client(Dns resolver) {
        return new OkHttpClient.Builder().proxy(Proxy.NO_PROXY)
                .followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
                .connectTimeout(5, TimeUnit.SECONDS).readTimeout(10, TimeUnit.SECONDS)
                .callTimeout(15, TimeUnit.SECONDS)
                .dns(host -> {
                    List<InetAddress> addresses = resolver.lookup(host);
                    if (addresses.isEmpty() || addresses.stream().anyMatch(RemoteFetchGuard::blocked)) {
                        throw new UnknownHostException("Disallowed media destination");
                    }
                    return addresses;
                }).build();
    }

    static byte[] fetch(String url, int maxBytes, String what) {
        return fetch(CLIENT, url, maxBytes, what);
    }

    static byte[] fetch(OkHttpClient client, String url, int maxBytes, String what) {
        HttpUrl parsed = url == null ? null : HttpUrl.parse(url);
        if (parsed == null || !parsed.isHttps() || !parsed.username().isEmpty() || !parsed.password().isEmpty()) {
            throw new IllegalArgumentException(what + " URL must be a direct HTTPS URL without credentials");
        }
        // OkHttp can skip its DNS hook for literal IPs.
        if (parsed.host().contains(":") || parsed.host().matches("[0-9.]+")) {
            try {
                if (blocked(InetAddress.getByName(parsed.host()))) {
                    throw new IllegalArgumentException(what + " URL has a disallowed destination");
                }
            } catch (UnknownHostException e) {
                throw new IllegalArgumentException("Invalid " + what + " destination");
            }
        }
        try (Response response = client.newCall(new Request.Builder().url(parsed).build()).execute()) {
            return read(response, maxBytes, what);
        } catch (IOException e) {
            throw new IllegalArgumentException("Failed to download " + what + " from URL");
        }
    }

    static byte[] read(Response response, int maxBytes, String what) throws IOException {
        if (response.code() != 200) {
            throw new IllegalArgumentException("Refusing " + what + " URL: HTTP " + response.code());
        }
        if (maxBytes < 0 || maxBytes == Integer.MAX_VALUE) {
            throw new IllegalArgumentException("Invalid download size limit");
        }
        if (response.body() == null) throw new IllegalArgumentException("Empty " + what + " response");
        byte[] bytes = response.body().byteStream().readNBytes(maxBytes + 1);
        if (bytes.length > maxBytes) throw new IllegalArgumentException(what + " exceeds the maximum allowed size");
        return bytes;
    }

    static boolean blocked(InetAddress address) {
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            long value = 0;
            for (byte b : bytes) value = (value << 8) | (b & 255);
            for (int[] range : BLOCKED_V4) {
                long prefix = 0;
                for (int i = 0; i < 4; i++) prefix = (prefix << 8) | range[i];
                long mask = (0xffffffffL << (32 - range[4])) & 0xffffffffL;
                if ((value & mask) == (prefix & mask)) return true;
            }
            return false;
        }
        if (bytes.length != 16) return true;
        // Accept global unicast only; translation, mapped and tunnel ranges are not media destinations.
        if ((bytes[0] & 0xe0) != 0x20) return true;
        if ((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x02) return true;
        if ((bytes[0] & 255) == 0x20 && (bytes[1] & 255) == 0x01) {
            if ((bytes[2] & 0xfe) == 0) return true;
            if ((bytes[2] & 255) == 0x0d && (bytes[3] & 255) == 0xb8) return true;
        }
        return (bytes[0] & 255) == 0x3f && (bytes[1] & 255) == 0xff && (bytes[2] & 0xf0) == 0;
    }
}
