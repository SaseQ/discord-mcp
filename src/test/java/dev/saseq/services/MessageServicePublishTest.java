package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.RateLimitedException;
import net.dv8tion.jda.api.requests.ErrorResponse;
import net.dv8tion.jda.api.requests.Response;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import okhttp3.MediaType;
import okhttp3.Protocol;
import okhttp3.Request;
import okhttp3.ResponseBody;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.EnumSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

class MessageServicePublishTest {
    private static final String CHANNEL_ID = "123456789012345678";
    private static final String MESSAGE_ID = "234567890123456789";
    private static final String ORIGINAL_URL = "https://discord.com/channels/1/2/3";
    private static final String PUBLISHED_URL = "https://discord.com/channels/1/2/4";
    private final JDA jda = mock(JDA.class);
    private final NewsChannel channel = mock(NewsChannel.class);
    private final MessageService service = new MessageService(jda);

    @Test
    @SuppressWarnings("unchecked")
    void publishesAndReturnsThePublishedMessageLink() throws Exception {
        Message message = stubMessage();
        Message published = mock(Message.class);
        RestAction<Message> action = mock(RestAction.class);
        when(message.getFlags()).thenReturn(EnumSet.noneOf(Message.MessageFlag.class));
        when(message.crosspost()).thenReturn(action);
        when(action.complete(false)).thenReturn(published);
        when(published.getJumpUrl()).thenReturn(PUBLISHED_URL);

        assertThat(service.publishMessage(CHANNEL_ID, MESSAGE_ID))
                .isEqualTo("Message published successfully. Message link: " + PUBLISHED_URL);
        verify(action).complete(false);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidChannelIdBeforeLookup(String value) {
        assertThatThrownBy(() -> service.publishMessage(value, MESSAGE_ID))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("channelId");
        verifyNoInteractions(jda);
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {" ", "\t\n"})
    void rejectsInvalidMessageIdBeforeLookup(String value) {
        assertThatThrownBy(() -> service.publishMessage(CHANNEL_ID, value))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("messageId");
        verifyNoInteractions(jda);
    }

    @Test
    void rejectsUnavailableAnnouncementChannelWithoutFallback() {
        assertThatThrownBy(() -> service.publishMessage(CHANNEL_ID, MESSAGE_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Channel not found or is not an announcement channel");
        verify(jda).getNewsChannelById(CHANNEL_ID);
        verifyNoMoreInteractions(jda);
        verifyNoInteractions(channel);
    }

    @Test
    void reportsAlreadyPublishedWithoutCrossposting() {
        Message message = stubMessage();
        when(message.getFlags()).thenReturn(EnumSet.of(Message.MessageFlag.CROSSPOSTED));
        when(message.getJumpUrl()).thenReturn(ORIGINAL_URL);
        assertThat(service.publishMessage(CHANNEL_ID, MESSAGE_ID))
                .isEqualTo("Message was already published. Message link: " + ORIGINAL_URL);
        verify(message, never()).crosspost();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorResponse.class, names = {
            "UNKNOWN_MESSAGE", "MISSING_ACCESS", "MISSING_PERMISSIONS", "ALREADY_CROSSPOSTED"})
    void propagatesRetrievalErrorsWithoutPublishing(ErrorResponse response) {
        Message message = stubMessage();
        ErrorResponseException error = discordError(response);
        when(channel.retrieveMessageById(MESSAGE_ID).complete()).thenThrow(error);
        assertThatThrownBy(() -> service.publishMessage(CHANNEL_ID, MESSAGE_ID)).isSameAs(error);
        verify(message, never()).crosspost();
    }

    @ParameterizedTest
    @EnumSource(value = ErrorResponse.class, names = {"UNKNOWN_MESSAGE", "MISSING_ACCESS", "MISSING_PERMISSIONS"})
    void propagatesOtherPublishErrors(ErrorResponse response) throws Exception {
        RestAction<Message> action = stubPublish();
        ErrorResponseException error = discordError(response);
        when(action.complete(false)).thenThrow(error);
        assertThatThrownBy(() -> service.publishMessage(CHANNEL_ID, MESSAGE_ID)).isSameAs(error);
        verify(action).complete(false);
    }

    @Test
    void propagatesPublishTransportFailure() throws Exception {
        RestAction<Message> action = stubPublish();
        RuntimeException error = new IllegalStateException("Connection lost");
        when(action.complete(false)).thenThrow(error);
        assertThatThrownBy(() -> service.publishMessage(CHANNEL_ID, MESSAGE_ID)).isSameAs(error);
        verify(action).complete(false);
    }

    @Test
    void reportsAlreadyPublishedAfterConcurrentPublish() throws Exception {
        RestAction<Message> action = stubPublish();
        when(action.complete(false)).thenThrow(discordError(ErrorResponse.ALREADY_CROSSPOSTED));
        assertThat(service.publishMessage(CHANNEL_ID, MESSAGE_ID))
                .isEqualTo("Message was already published. Message link: " + ORIGINAL_URL);
        verify(action).complete(false);
    }

    @Test
    void reportsPublishRateLimitWithExactDelayAndCause() throws Exception {
        RestAction<Message> action = stubPublish();
        RateLimitedException error = new RateLimitedException("crosspost", 2500);
        when(action.complete(false)).thenThrow(error);
        assertThatThrownBy(() -> service.publishMessage(CHANNEL_ID, MESSAGE_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Discord is rate-limiting publishing; retry after 2500 ms")
                .hasCause(error);
        verify(action).complete(false);
        verifyNoMoreInteractions(action);
    }

    @Test
    void discoversPublishingWithTwoRequiredStringIdsAndBroadcastWarning() {
        var callbacks = MethodToolCallbackProvider.builder().toolObjects(service).build().getToolCallbacks();
        var publishing = java.util.Arrays.stream(callbacks)
                .filter(callback -> callback.getToolDefinition().name().equals("publish_message")).toList();
        assertThat(publishing).hasSize(1);
        var definition = publishing.get(0).getToolDefinition();
        assertThat(definition.description()).contains("broadcasts beyond the source server");
        DataObject schema = DataObject.fromJson(definition.inputSchema());
        assertThat(schema.getObject("properties").keys()).containsExactlyInAnyOrder("channelId", "messageId");
        assertThat(schema.getArray("required").toList()).containsExactlyInAnyOrder("channelId", "messageId");
        assertThat(schema.getObject("properties").getObject("channelId").getString("type")).isEqualTo("string");
        assertThat(schema.getObject("properties").getObject("messageId").getString("type")).isEqualTo("string");
    }

    @SuppressWarnings("unchecked")
    private RestAction<Message> stubPublish() {
        Message message = stubMessage();
        RestAction<Message> action = mock(RestAction.class);
        when(message.getFlags()).thenReturn(EnumSet.noneOf(Message.MessageFlag.class));
        when(message.getJumpUrl()).thenReturn(ORIGINAL_URL);
        when(message.crosspost()).thenReturn(action);
        return action;
    }

    private ErrorResponseException discordError(ErrorResponse error) {
        String json = "{\"code\":" + error.getCode() + ",\"message\":\"Synthetic Discord error\"}";
        okhttp3.Response raw = new okhttp3.Response.Builder()
                .request(new Request.Builder().url("https://discord.com/api/v10/channels/" + CHANNEL_ID).build())
                .protocol(Protocol.HTTP_1_1).code(400).message("Bad Request")
                .body(ResponseBody.create(json, MediaType.get("application/json"))).build();
        return ErrorResponseException.create(error, new Response(raw, 400, "Bad Request", -1, Set.of()));
    }

    @SuppressWarnings("unchecked")
    private Message stubMessage() {
        Message message = mock(Message.class);
        RestAction<Message> retrieval = mock(RestAction.class);
        when(jda.getNewsChannelById(CHANNEL_ID)).thenReturn(channel);
        when(channel.retrieveMessageById(MESSAGE_ID)).thenReturn(retrieval);
        when(retrieval.complete()).thenReturn(message);
        return message;
    }
}
