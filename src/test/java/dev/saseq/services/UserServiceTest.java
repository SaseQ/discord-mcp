package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.PrivateChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.utils.cache.MemberCacheView;
import net.dv8tion.jda.api.utils.concurrent.Task;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class UserServiceTest {

    private static final String GUILD_ID = "987654321098765432";

    private JDA jda;
    private Guild guild;
    private UserService userService;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        guild = mock(Guild.class);
        userService = new UserService(jda);

        when(jda.getGuildById(GUILD_ID)).thenReturn(guild);
    }

    @Test
    void getMemberByIdRetrievesFreshMemberDetails() {
        Member member = member("123456789012345678", "alice", "Alice", "Ali", "Ali");
        CacheRestAction<Member> lookup = memberLookup(member);

        when(guild.retrieveMemberById("123456789012345678")).thenReturn(lookup);

        String result = userService.getMemberById("123456789012345678", GUILD_ID);

        assertThat(result)
                .contains("Member found")
                .contains("User ID: 123456789012345678")
                .contains("Username: alice")
                .contains("Global: Alice")
                .contains("Nickname: Ali")
                .contains("Effective: Ali")
                .contains("Roles: none");
        verify(lookup).useCache(false);
    }

    @Test
    void getMemberByIdRejectsMissingMember() {
        CacheRestAction<Member> lookup = memberLookup(null);

        when(guild.retrieveMemberById("123456789012345678")).thenReturn(lookup);

        assertThatThrownBy(() -> userService.getMemberById("123456789012345678", GUILD_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("No guild member found with userId 123456789012345678");
    }

    @Test
    void searchMembersUsesExactSnowflakeLookupBeforeCacheOrPrefixSearch() {
        Member member = member("123456789012345678", "alice", "Alice", "Ali", "Ali");
        CacheRestAction<Member> lookup = memberLookup(member);

        when(guild.retrieveMemberById("123456789012345678")).thenReturn(lookup);

        String result = userService.searchMembers("123456789012345678", "1", GUILD_ID);

        assertThat(result)
                .contains("Found 1 member candidate")
                .contains("User ID: 123456789012345678")
                .contains("Username: alice");
        verify(lookup).useCache(false);
        verify(guild, never()).getMemberCache();
        verify(guild, never()).retrieveMembersByPrefix(anyString(), anyInt());
    }

    @Test
    void searchMembersReturnsCacheMatchesWithoutPrefixLookupWhenLimitIsReached() {
        Member member = member("123456789012345678", "alice", "Alice", "Ali", "Ali");
        MemberCacheView cache = memberCache(member);

        when(guild.getMemberCache()).thenReturn(cache);

        String result = userService.searchMembers("alice", "1", GUILD_ID);

        assertThat(result)
                .contains("Found 1 member candidate")
                .contains("User ID: 123456789012345678")
                .contains("Username: alice")
                .doesNotContain("Joined:")
                .doesNotContain("Roles:");
        verify(guild, never()).retrieveMembersByPrefix(anyString(), anyInt());
    }

    @Test
    void searchMembersFallsBackToLivePrefixSearchWhenCacheMisses() {
        Member member = member("123456789012345678", "alice", "Alice", null, "Alice");
        MemberCacheView cache = memberCache();
        Task<List<Member>> prefixSearch = prefixSearch(member);

        when(guild.getMemberCache()).thenReturn(cache);
        when(guild.retrieveMembersByPrefix("alice", 25)).thenReturn(prefixSearch);

        String result = userService.searchMembers("alice", null, GUILD_ID);

        assertThat(result)
                .contains("Found 1 member candidate")
                .contains("User ID: 123456789012345678")
                .contains("Nickname: none");
    }

    @Test
    void searchMembersReturnsCacheMatchesWhenLivePrefixSearchFails() {
        Member member = member("123456789012345678", "alice", "Alice", null, "Alice");
        MemberCacheView cache = memberCache(member);
        Task<List<Member>> prefixSearch = failingPrefixSearch();

        when(guild.getMemberCache()).thenReturn(cache);
        when(guild.retrieveMembersByPrefix("alice", 25)).thenReturn(prefixSearch);

        String result = userService.searchMembers("alice", null, GUILD_ID);

        assertThat(result)
                .contains("Found 1 member candidate")
                .contains("User ID: 123456789012345678");
    }

    @Test
    void searchMembersReturnsNoMatchesWhenCacheAndPrefixSearchMiss() {
        MemberCacheView cache = memberCache();
        Task<List<Member>> prefixSearch = prefixSearch();

        when(guild.getMemberCache()).thenReturn(cache);
        when(guild.retrieveMembersByPrefix("missing", 25)).thenReturn(prefixSearch);

        String result = userService.searchMembers("missing", null, GUILD_ID);

        assertThat(result).isEqualTo("No members found for query missing");
    }

    @Test
    void searchMembersRejectsInvalidCandidateLimit() {
        assertThatThrownBy(() -> userService.searchMembers("alice", "0", GUILD_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("count must be between 1 and 100");
    }

    @Test
    void readPrivateMessagesIncludesStableAuthorIdentityFields() {
        Member targetMember = member("123456789012345678", "alice", "Alice", null, "Alice");
        User targetUser = targetMember.getUser();
        PrivateChannel privateChannel = mock(PrivateChannel.class);
        MessageHistory history = mock(MessageHistory.class);
        Message message = message("111111111111111111", "234567890123456789", "bob", "Bob", "hello");
        CacheRestAction<Member> memberLookup = memberLookup(targetMember);
        CacheRestAction<PrivateChannel> privateChannelLookup = privateChannelLookup(privateChannel);
        RestAction<List<Message>> retrievePast = restAction(List.of(message));

        when(jda.getGuilds()).thenReturn(List.of(guild));
        when(guild.retrieveMemberById("123456789012345678")).thenReturn(memberLookup);
        when(targetUser.openPrivateChannel()).thenReturn(privateChannelLookup);
        when(privateChannel.getHistory()).thenReturn(history);
        when(history.retrievePast(1)).thenReturn(retrievePast);

        String result = userService.readPrivateMessages("123456789012345678", "1", null, null, null);

        assertThat(result)
                .contains("Retrieved 1 messages")
                .contains("(ID: 111111111111111111) **[bob]**")
                .contains("Author ID: 234567890123456789")
                .doesNotContain("Global:")
                .doesNotContain("Nickname:")
                .doesNotContain("Effective:");
    }

    @SuppressWarnings("unchecked")
    private CacheRestAction<Member> memberLookup(Member member) {
        CacheRestAction<Member> lookup = mock(CacheRestAction.class);
        when(lookup.useCache(false)).thenReturn(lookup);
        when(lookup.complete()).thenReturn(member);
        return lookup;
    }

    @SuppressWarnings("unchecked")
    private CacheRestAction<PrivateChannel> privateChannelLookup(PrivateChannel privateChannel) {
        CacheRestAction<PrivateChannel> lookup = mock(CacheRestAction.class);
        when(lookup.complete()).thenReturn(privateChannel);
        return lookup;
    }

    @SuppressWarnings("unchecked")
    private RestAction<List<Message>> restAction(List<Message> messages) {
        RestAction<List<Message>> action = mock(RestAction.class);
        when(action.complete()).thenReturn(messages);
        return action;
    }

    private MemberCacheView memberCache(Member... members) {
        MemberCacheView cache = mock(MemberCacheView.class);
        when(cache.iterator()).thenReturn(List.of(members).iterator());
        return cache;
    }

    @SuppressWarnings("unchecked")
    private Task<List<Member>> prefixSearch(Member... members) {
        Task<List<Member>> task = mock(Task.class);
        when(task.get()).thenReturn(List.of(members));
        return task;
    }

    @SuppressWarnings("unchecked")
    private Task<List<Member>> failingPrefixSearch() {
        Task<List<Member>> task = mock(Task.class);
        when(task.get()).thenThrow(new RuntimeException("prefix unavailable"));
        return task;
    }

    private Member member(String id, String username, String globalName, String nickname, String effectiveName) {
        User user = mock(User.class);
        when(user.getId()).thenReturn(id);
        when(user.getName()).thenReturn(username);
        when(user.getGlobalName()).thenReturn(globalName);
        when(user.getEffectiveName()).thenReturn(globalName != null ? globalName : username);
        when(user.isBot()).thenReturn(false);

        Member member = mock(Member.class);
        when(member.getId()).thenReturn(id);
        when(member.getUser()).thenReturn(user);
        when(member.getNickname()).thenReturn(nickname);
        when(member.getEffectiveName()).thenReturn(effectiveName);
        when(member.getTimeJoined()).thenReturn(OffsetDateTime.parse("2026-05-26T00:00:00Z"));
        when(member.getRoles()).thenReturn(List.of());
        return member;
    }

    private Message message(String messageId, String authorId, String username, String globalName, String content) {
        User author = mock(User.class);
        when(author.getId()).thenReturn(authorId);
        when(author.getName()).thenReturn(username);
        when(author.getGlobalName()).thenReturn(globalName);
        when(author.getEffectiveName()).thenReturn(globalName != null ? globalName : username);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(messageId);
        when(message.getAuthor()).thenReturn(author);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-05-26T00:00:00Z"));
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }
}
