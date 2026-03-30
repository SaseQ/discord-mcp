package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.ThreadChannel;
import net.dv8tion.jda.api.entities.emoji.Emoji;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.requests.restaction.MessageEditAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MessageServiceTest {

    @Mock private JDA jda;
    @Mock private TextChannel textChannel;
    @Mock private Message message;

    private MessageService service;

    @BeforeEach
    void setUp() {
        service = new MessageService(jda);
    }

    @Test
    void sendMessage_nullChannelId_throws() {
        assertThatThrownBy(() -> service.sendMessage(null, "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelId");
    }

    @Test
    void sendMessage_nullMessage_throws() {
        assertThatThrownBy(() -> service.sendMessage("123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("message");
    }

    @Test
    void sendMessage_channelNotFound_throws() {
        when(jda.getTextChannelById("123")).thenReturn(null);
        when(jda.getThreadChannelById("123")).thenReturn(null);

        assertThatThrownBy(() -> service.sendMessage("123", "hello"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel not found");
    }

    @Test
    void sendMessage_happyPath() {
        MessageCreateAction action = mock(MessageCreateAction.class);
        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.sendMessage("hello")).thenReturn(action);
        when(action.complete()).thenReturn(message);
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");

        String result = service.sendMessage("123", "hello");

        assertThat(result).contains("Message sent successfully");
        assertThat(result).contains("https://discord.com/channels/1/2/3");
    }

    @Test
    void sendMessage_threadChannel() {
        ThreadChannel thread = mock(ThreadChannel.class);
        MessageCreateAction action = mock(MessageCreateAction.class);
        when(jda.getTextChannelById("456")).thenReturn(null);
        when(jda.getThreadChannelById("456")).thenReturn(thread);
        when(thread.sendMessage("hello")).thenReturn(action);
        when(action.complete()).thenReturn(message);
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");

        String result = service.sendMessage("456", "hello");

        assertThat(result).contains("Message sent successfully");
    }

    @Test
    void editMessage_happyPath() {
        MessageEditAction editAction = mock(MessageEditAction.class);
        RestAction<Message> retrieveAction = mock(RestAction.class);
        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.retrieveMessageById("456")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(message);
        when(message.editMessage("updated")).thenReturn(editAction);
        when(editAction.complete()).thenReturn(message);
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");

        String result = service.editMessage("123", "456", "updated");

        assertThat(result).contains("Message edited successfully");
    }

    @Test
    void editMessage_nullMessageId_throws() {
        assertThatThrownBy(() -> service.editMessage("123", null, "new"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("messageId");
    }

    @Test
    void deleteMessage_happyPath() {
        RestAction<Message> retrieveAction = mock(RestAction.class);
        AuditableRestAction<Void> deleteAction = mock(AuditableRestAction.class);
        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.retrieveMessageById("456")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(message);
        when(message.delete()).thenReturn(deleteAction);

        String result = service.deleteMessage("123", "456");

        assertThat(result).contains("Message deleted successfully");
        verify(deleteAction).queue();
    }

    @Test
    void readMessages_defaultCount() {
        var history = mock(net.dv8tion.jda.api.entities.MessageHistory.class);
        RestAction<List<Message>> retrieveAction = mock(RestAction.class);

        User author = mock(User.class);
        when(author.getName()).thenReturn("testuser");

        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.getHistory()).thenReturn(history);
        when(history.retrievePast(100)).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(List.of(message));
        when(message.getAuthor()).thenReturn(author);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.now());
        when(message.getContentDisplay()).thenReturn("test content");
        when(message.getId()).thenReturn("789");
        when(message.getAttachments()).thenReturn(Collections.emptyList());

        String result = service.readMessages("123", null);

        assertThat(result).contains("Retrieved 1 messages");
    }

    @Test
    void readMessages_customCount() {
        var history = mock(net.dv8tion.jda.api.entities.MessageHistory.class);
        RestAction<List<Message>> retrieveAction = mock(RestAction.class);

        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.getHistory()).thenReturn(history);
        when(history.retrievePast(10)).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(Collections.emptyList());

        String result = service.readMessages("123", "10");

        assertThat(result).contains("Retrieved 0 messages");
    }

    @Test
    void addReaction_nullEmoji_throws() {
        assertThatThrownBy(() -> service.addReaction("123", "456", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("emoji");
    }

    @Test
    void addReaction_happyPath() {
        RestAction<Message> retrieveAction = mock(RestAction.class);
        RestAction<Void> reactionAction = mock(RestAction.class);
        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.retrieveMessageById("456")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(message);
        when(message.addReaction(any(Emoji.class))).thenReturn(reactionAction);
        when(message.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");

        String result = service.addReaction("123", "456", "\uD83D\uDC4D");

        assertThat(result).contains("Added reaction successfully");
        verify(reactionAction).queue();
    }

    @Test
    void getAttachment_noAttachments() {
        RestAction<Message> retrieveAction = mock(RestAction.class);
        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.retrieveMessageById("456")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(message);
        when(message.getAttachments()).thenReturn(Collections.emptyList());

        String result = service.getAttachment("123", "456", null);

        assertThat(result).contains("no attachments");
    }

    @Test
    void getAttachment_specificNotFound_throws() {
        RestAction<Message> retrieveAction = mock(RestAction.class);
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(jda.getTextChannelById("123")).thenReturn(textChannel);
        when(textChannel.retrieveMessageById("456")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(message);
        when(message.getAttachments()).thenReturn(List.of(attachment));
        when(attachment.getId()).thenReturn("999");

        assertThatThrownBy(() -> service.getAttachment("123", "456", "111"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Attachment not found");
    }
}
