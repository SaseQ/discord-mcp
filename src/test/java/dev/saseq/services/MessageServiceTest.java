package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.RestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class MessageServiceTest {

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
                "Alice",
                "Alice",
                member("Ali", "Ali"),
                "hello"
        );
        Message directAuthorMessage = message(
                "222222222222222222",
                "234567890123456789",
                "bob",
                "Bob",
                "Bob",
                null,
                "hi"
        );
        RestAction<List<Message>> retrievePast = restAction(List.of(guildMessage, directAuthorMessage));

        when(jda.getTextChannelById("345678901234567890")).thenReturn(channel);
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(2)).thenReturn(retrievePast);

        String result = messageService.readMessages("345678901234567890", "2", null, null, null);

        assertThat(result)
                .contains("Retrieved 2 messages")
                .contains("(ID: 111111111111111111) **[alice]**")
                .contains("Author ID: 123456789012345678")
                .contains("(ID: 222222222222222222) **[bob]**")
                .contains("Author ID: 234567890123456789")
                .doesNotContain("Global:")
                .doesNotContain("Nickname:")
                .doesNotContain("Effective:");
    }

    @SuppressWarnings("unchecked")
    private RestAction<List<Message>> restAction(List<Message> messages) {
        RestAction<List<Message>> action = mock(RestAction.class);
        when(action.complete()).thenReturn(messages);
        return action;
    }

    private Message message(String messageId, String authorId, String username, String globalName,
                            String effectiveName, Member member, String content) {
        User author = mock(User.class);
        when(author.getId()).thenReturn(authorId);
        when(author.getName()).thenReturn(username);
        when(author.getGlobalName()).thenReturn(globalName);
        when(author.getEffectiveName()).thenReturn(effectiveName);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(messageId);
        when(message.getAuthor()).thenReturn(author);
        when(message.getMember()).thenReturn(member);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-05-26T00:00:00Z"));
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }

    private Member member(String nickname, String effectiveName) {
        Member member = mock(Member.class);
        when(member.getNickname()).thenReturn(nickname);
        when(member.getEffectiveName()).thenReturn(effectiveName);
        return member;
    }
}
