package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    private static final String CHANNEL_ID = "345678901234567890";

    private JDA jda;
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        messageService = new MessageService(jda);
    }

    @Test
    void readMessagesIncludesStableAuthorIdentityFields() {
        TextChannel channel = mock(TextChannel.class);
        MessageHistory history = mock(MessageHistory.class);
        Message guildMessage = message(
                "111111111111111111",
                "123456789012345678",
                "alice",
                "hello"
        );
        Message directAuthorMessage = message(
                "222222222222222222",
                "234567890123456789",
                "bob",
                "hi"
        );
        RestAction<List<Message>> retrievePast = restAction(List.of(guildMessage, directAuthorMessage));

        when(jda.getTextChannelById("345678901234567890")).thenReturn(channel);
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(2)).thenReturn(retrievePast);

        String result = messageService.readMessages("345678901234567890", "2", null, null, null);

        assertThat(result).isEqualTo("**Retrieved 2 messages:** \n"
                + "- (ID: 111111111111111111) **[alice]** (Author ID: 123456789012345678) `2026-05-26T00:00Z`: ```hello```\n"
                + "- (ID: 222222222222222222) **[bob]** (Author ID: 234567890123456789) `2026-05-26T00:00Z`: ```hi```");
    }

    @Test
    void sendFileRefusesLocalPathsWhenNoRootIsConfigured(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("ok.txt"), "hello");
        stubChannel();

        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, file.toString(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_FILE_ROOT");
    }

    @Test
    void sendFileRefusesAFilesystemRootAsTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("ok.txt"), "hello");
        messageService.fileRoot = dir.getRoot().toString();
        stubChannel();

        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, file.toString(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a filesystem root");
    }

    @Test
    void sendFileRefusesTraversalOutOfTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path secret = Files.writeString(dir.resolve("secret.env"), "DISCORD_TOKEN=hunter2");
        messageService.fileRoot = root.toString();
        stubChannel();

        String traversal = root.resolve("..").resolve(secret.getFileName()).toString();
        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, traversal, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
    }

    @Test
    void sendFileRefusesASymlinkPointingOutOfTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path secret = Files.writeString(dir.resolve("secret.env"), "DISCORD_TOKEN=hunter2");
        Path link = root.resolve("innocent.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation not permitted on this host");
        }
        messageService.fileRoot = root.toString();
        stubChannel();

        // A lexical normalize() would see uploads/innocent.txt and allow this.
        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, link.toString(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
    }

    @Test
    void sendFileUploadsAFileInsideTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path file = Files.writeString(root.resolve("poster.png"), "not really a png");
        messageService.fileRoot = root.toString();

        TextChannel channel = stubChannel();
        MessageCreateAction action = mock(MessageCreateAction.class);
        Message sent = mock(Message.class);
        when(channel.sendFiles(any(FileUpload.class))).thenReturn(action);
        when(action.complete()).thenReturn(sent);
        when(sent.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");

        String result = messageService.sendFile(CHANNEL_ID, file.toString(), null, null, null, null);

        assertThat(result).contains("File sent successfully");
    }

    private TextChannel stubChannel() {
        TextChannel channel = mock(TextChannel.class);
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(channel);
        return channel;
    }

    @SuppressWarnings("unchecked")
    private RestAction<List<Message>> restAction(List<Message> messages) {
        RestAction<List<Message>> action = mock(RestAction.class);
        when(action.complete()).thenReturn(messages);
        return action;
    }

    private Message message(String messageId, String authorId, String username, String content) {
        User author = mock(User.class);
        when(author.getId()).thenReturn(authorId);
        when(author.getName()).thenReturn(username);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(messageId);
        when(message.getAuthor()).thenReturn(author);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-05-26T00:00:00Z"));
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }
}
