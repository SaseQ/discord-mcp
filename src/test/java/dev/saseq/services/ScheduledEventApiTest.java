package dev.saseq.services;

import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.internal.JDAImpl;
import net.dv8tion.jda.internal.utils.config.AuthorizationConfig;
import net.dv8tion.jda.api.utils.data.DataObject;
import okhttp3.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.util.ArrayList;
import java.util.List;
import java.util.ArrayDeque;
import java.util.Deque;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

@org.junit.jupiter.api.Timeout(10)
class ScheduledEventApiTest {
    @Test
    void getUsesJdaAuthenticationAndHandlesProviderErrors() {
        try (var transport = new DiscordTransport()) {
            transport.replies.add("{\"id\":\"234\"}");
            var api = new ScheduledEventApi(transport.jda);
            assertThat(api.get("123", "234").getString("id")).isEqualTo("234");
            assertThat(transport.requests.get(0).method()).isEqualTo("GET");
            assertThat(transport.requests.get(0).url().encodedPath()).endsWith("/guilds/123/scheduled-events/234");
            assertThat(transport.requests.get(0).header("Authorization")).isEqualTo("Bot synthetic-test-token");
            transport.status = 403;
            transport.replies.add("{\"code\":50013,\"message\":\"Missing Permissions\"}");
            assertThatThrownBy(() -> api.get("123", "234")).isInstanceOf(ErrorResponseException.class);
        }
    }

    @Test
    void acceptedWriteWithUnreadableBodyDoesNotBecomeARejectedWrite() {
        try (var transport = new DiscordTransport()) {
            transport.replies.add("not-json");
            assertThat(new ScheduledEventApi(transport.jda).edit("123", "234", DataObject.empty()).keys()).isEmpty();
            assertThat(transport.requests).hasSize(1);
        }
    }

    @Test
    void transportFailureReportsUnknownOutcome() {
        try (var transport = new DiscordTransport()) {
            transport.failWrites = true;
            assertThatThrownBy(() -> new ScheduledEventApi(transport.jda).edit("123", "234", DataObject.empty()))
                    .isInstanceOf(IllegalStateException.class).hasMessageContaining("outcome is unknown");
        }
    }

    @Test
    void unreadableGetAndListFailWithoutHangingTheRequester() {
        try (var transport = new DiscordTransport()) {
            var api = new ScheduledEventApi(transport.jda);
            transport.replies.add("not-json");
            assertThatThrownBy(() -> api.get("123", "234")).hasMessage("Event response unavailable");
            transport.replies.add("not-json");
            assertThatThrownBy(() -> api.list("123", false)).hasMessage("Event list response unavailable");
            assertThat(transport.requests.get(1).url().queryParameter("with_user_count")).isEqualTo("false");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"../users/@me", "123?x=y", "123/456", "+123", "18446744073709551616", "١٢٣"})
    void rejectsInvalidRouteIdsBeforeRequests(String value) {
        try (var transport = new DiscordTransport()) {
            var api = new ScheduledEventApi(transport.jda);
            assertThatThrownBy(() -> api.get(value, "234")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> api.get("123", value)).isInstanceOf(IllegalArgumentException.class);
            assertThat(transport.requests).isEmpty();
        }
    }
}

// Real JDA requester and rate limiter, with all HTTP intercepted before any network I/O.
class DiscordTransport implements AutoCloseable {
    final JDAImpl jda;
    final List<Request> requests = new ArrayList<>();
    final List<String> bodies = new ArrayList<>();
    final Deque<String> replies = new ArrayDeque<>();
    int status = 200;
    int patchStatus = 200;
    boolean failWrites;

    DiscordTransport() {
        jda = spy(new JDAImpl(new AuthorizationConfig("synthetic-test-token")));
        OkHttpClient http = new OkHttpClient.Builder().addInterceptor(chain -> {
            Request request = chain.request();
            requests.add(request);
            okio.Buffer buffer = new okio.Buffer();
            if (request.body() != null) request.body().writeTo(buffer);
            bodies.add(buffer.readUtf8());
            if (failWrites && !request.method().equals("GET")) throw new java.io.IOException("synthetic connection failure");
            String body = replies.isEmpty() ? "{}" : replies.removeFirst();
            int code = request.method().equals("PATCH") ? patchStatus : status;
            return new Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(code)
                    .message(code == 200 ? "OK" : "Forbidden")
                    .body(ResponseBody.create(body, MediaType.get("application/json"))).build();
        }).build();
        doReturn(http).when(jda).getHttpClient();
        var threading = (net.dv8tion.jda.internal.utils.config.ThreadingConfig)
                org.springframework.test.util.ReflectionTestUtils.getField(jda, "threadConfig");
        threading.init(() -> "offline-event-test");
        jda.initRequester();
    }

    @Override public void close() { jda.shutdownNow(); }
}
