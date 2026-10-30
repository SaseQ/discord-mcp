package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.NewsChannel;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.channel.middleman.MessageChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessagePollData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EmptySource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceCreatePollTest {

    private static final String CHANNEL_ID = "123456789";
    private static final String MESSAGE_ID = "987654321";
    private static final String JUMP_URL = "https://discord.com/channels/1/" + CHANNEL_ID + "/" + MESSAGE_ID;

    @Mock
    private JDA jda;
    @Mock
    private TextChannel textChannel;
    @Mock
    private NewsChannel newsChannel;
    @Mock
    private ThreadChannel threadChannel;
    @Mock
    private MessageCreateAction sendAction;
    @Mock
    private Message sentMessage;

    private MessageService service;

    @BeforeEach
    void setUp() {
        service = new MessageService(jda);
    }

    @Test
    void sendsPollWithCustomOptionsAndReturnsIdAndJumpUrl() {
        givenTextChannelSendsPoll();

        String result = service.createPoll(CHANNEL_ID, "Favorite language?", List.of("Java", "Kotlin", "Go"), 48, true);

        MessagePollData payload = captureSentPayload();
        assertEquals("Favorite language?", payload.toData().getObject("question").getString("text"));
        assertEquals("Java", answerText(payload, 0));
        assertEquals("Kotlin", answerText(payload, 1));
        assertEquals("Go", answerText(payload, 2));
        assertEquals(48, payload.toData().getLong("duration"));
        assertTrue(payload.toData().getBoolean("allow_multiselect"));
        assertEquals("Poll sent successfully. Message ID: " + MESSAGE_ID + ". Message link: " + JUMP_URL, result);
        verify(textChannel, times(1)).sendMessagePoll(any(MessagePollData.class));
    }

    @Test
    void appliesDefaultDurationAndSingleAnswerWhenOptionsOmitted() {
        givenTextChannelSendsPoll();

        service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), null, null);

        MessagePollData payload = captureSentPayload();
        assertEquals(24, payload.toData().getLong("duration"));
        assertFalse(payload.toData().getBoolean("allow_multiselect"));
    }

    @ParameterizedTest
    @ValueSource(ints = {2, 10})
    void acceptsAnswerCountBoundaries(int count) {
        givenTextChannelSendsPoll();
        List<String> answers = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            answers.add("Answer " + i);
        }

        service.createPoll(CHANNEL_ID, "Question?", answers, null, null);

        assertEquals(count, captureSentPayload().toData().getArray("answers").length());
    }

    @Test
    void acceptsMaxLengthQuestionAndAnswer() {
        givenTextChannelSendsPoll();

        service.createPoll(CHANNEL_ID, "q".repeat(300), List.of("ok", "a".repeat(55)), null, null);

        MessagePollData payload = captureSentPayload();
        assertEquals(300, payload.toData().getObject("question").getString("text").length());
        assertEquals(55, answerText(payload, 1).length());
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 168})
    void acceptsDurationBoundaries(int hours) {
        givenTextChannelSendsPoll();

        service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), hours, null);

        assertEquals(hours, captureSentPayload().toData().getLong("duration"));
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void acceptsBothMultiselectValues(boolean multiselect) {
        givenTextChannelSendsPoll();

        service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), null, multiselect);

        assertEquals(multiselect, captureSentPayload().toData().getBoolean("allow_multiselect"));
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" "})
    void rejectsMissingChannelId(String channelId) {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(channelId, "Question?", List.of("A", "B"), null, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {"", "   "})
    void rejectsBlankQuestion(String question) {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(CHANNEL_ID, question, List.of("A", "B"), null, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @Test
    void rejectsOverlongQuestion() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(CHANNEL_ID, "q".repeat(301), List.of("A", "B"), null, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(ints = {0, 1, 11})
    void rejectsAnswerCountOutOfRange(Integer count) {
        final List<String> answers;
        if (count == null) {
            answers = null;
        } else {
            answers = new ArrayList<>();
            for (int i = 1; i <= count; i++) {
                answers.add("Answer " + i);
            }
        }

        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(CHANNEL_ID, "Question?", answers, null, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @ParameterizedTest
    @NullSource
    @EmptySource
    @ValueSource(strings = {" "})
    void rejectsBlankAnswerElement(String blank) {
        List<String> answers = new ArrayList<>();
        answers.add(blank);
        answers.add("B");

        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(CHANNEL_ID, "Question?", answers, null, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @Test
    void rejectsOverlongAnswer() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(CHANNEL_ID, "Question?", List.of("a".repeat(56), "B"), null, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @ParameterizedTest
    @ValueSource(ints = {-1, 0, 169})
    void rejectsOutOfRangeDuration(int hours) {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), hours, null));
        verifyNoLookup();
        verifyNoSend();
    }

    @Test
    void resolvesNewsChannelWhenTextLookupMisses() {
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(null);
        when(jda.getNewsChannelById(CHANNEL_ID)).thenReturn(newsChannel);
        givenChannelSendsPoll(newsChannel);

        String result = service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), null, null);

        assertTrue(result.contains(JUMP_URL));
        verify(newsChannel, times(1)).sendMessagePoll(any(MessagePollData.class));
    }

    @Test
    void resolvesThreadChannelWhenChannelLookupsMiss() {
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(null);
        when(jda.getNewsChannelById(CHANNEL_ID)).thenReturn(null);
        when(jda.getThreadChannelById(CHANNEL_ID)).thenReturn(threadChannel);
        givenChannelSendsPoll(threadChannel);

        String result = service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), null, null);

        assertTrue(result.contains(JUMP_URL));
        verify(threadChannel, times(1)).sendMessagePoll(any(MessagePollData.class));
    }

    @Test
    void rejectsUnknownChannelWithoutSending() {
        assertThrows(IllegalArgumentException.class,
                () -> service.createPoll("999999999", "Question?", List.of("A", "B"), null, null));
        verify(jda).getTextChannelById("999999999");
        verify(jda).getNewsChannelById("999999999");
        verify(jda).getThreadChannelById("999999999");
        verifyNoSend();
    }

    @Test
    void propagatesJdaSendFailureWithoutFalseSuccess() {
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(textChannel);
        when(textChannel.sendMessagePoll(any(MessagePollData.class))).thenReturn(sendAction);
        // JDA surfaces REST errors (e.g. missing permissions) from complete() as unchecked exceptions.
        when(sendAction.complete()).thenThrow(new RuntimeException("Missing Permissions (50035)"));

        RuntimeException ex = assertThrows(RuntimeException.class,
                () -> service.createPoll(CHANNEL_ID, "Question?", List.of("A", "B"), null, null));

        assertTrue(ex.getMessage().contains("Missing Permissions"));
        verify(textChannel, times(1)).sendMessagePoll(any(MessagePollData.class));
    }

    private void givenTextChannelSendsPoll() {
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(textChannel);
        givenChannelSendsPoll(textChannel);
    }

    private void givenChannelSendsPoll(MessageChannel channel) {
        when(channel.sendMessagePoll(any(MessagePollData.class))).thenReturn(sendAction);
        when(sendAction.complete()).thenReturn(sentMessage);
        when(sentMessage.getId()).thenReturn(MESSAGE_ID);
        when(sentMessage.getJumpUrl()).thenReturn(JUMP_URL);
    }

    private MessagePollData captureSentPayload() {
        ArgumentCaptor<MessagePollData> captor = ArgumentCaptor.forClass(MessagePollData.class);
        verify(textChannel).sendMessagePoll(captor.capture());
        return captor.getValue();
    }

    private static String answerText(MessagePollData payload, int index) {
        return payload.toData().getArray("answers").getObject(index).getObject("poll_media").getString("text");
    }

    private void verifyNoSend() {
        verifyNoInteractions(textChannel, newsChannel, threadChannel);
    }

    private void verifyNoLookup() {
        verify(jda, never()).getTextChannelById(any());
        verify(jda, never()).getNewsChannelById(any());
        verify(jda, never()).getThreadChannelById(any());
    }
}
