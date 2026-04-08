package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import net.dv8tion.jda.api.requests.restaction.AuditableRestAction;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import net.dv8tion.jda.api.requests.restaction.pagination.BanPaginationAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.Collections;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ModerationServiceTest {

    @Mock private JDA jda;
    @Mock private Guild guild;
    @Mock private Member member;

    private ModerationService service;

    @BeforeEach
    void setUp() {
        service = new ModerationService(jda);
        ReflectionTestUtils.setField(service, "defaultGuildId", "");
    }

    @Test
    void kickMember_nullGuildId_throws() {
        assertThatThrownBy(() -> service.kickMember(null, "user1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guildId cannot be null");
    }

    @Test
    void kickMember_nullUserId_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.kickMember("123", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId cannot be null");
    }

    @Test
    void kickMember_happyPath() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        AuditableRestAction<Void> kickAction = mock(AuditableRestAction.class);
        User user = mock(User.class);

        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);
        when(guild.kick(member)).thenReturn(kickAction);
        when(kickAction.reason(null)).thenReturn(kickAction);
        when(kickAction.complete()).thenReturn(null);
        when(member.getUser()).thenReturn(user);
        when(user.getName()).thenReturn("baduser");

        String result = service.kickMember("123", "user1", null);

        assertThat(result).contains("Successfully kicked user");
        assertThat(result).contains("baduser");
    }

    @Test
    void kickMember_hierarchyException_throws() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        AuditableRestAction<Void> kickAction = mock(AuditableRestAction.class);

        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);
        when(guild.kick(member)).thenReturn(kickAction);
        when(kickAction.reason(null)).thenReturn(kickAction);
        when(kickAction.complete()).thenThrow(mock(HierarchyException.class));

        assertThatThrownBy(() -> service.kickMember("123", "user1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("higher or equal role");
    }

    @Test
    void banMember_nullUserId_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.banMember("123", null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("userId cannot be null");
    }

    @Test
    void banMember_deleteSecondsOutOfRange_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.banMember("123", "user1", "999999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleteMessageSeconds must be between");
    }

    @Test
    void banMember_negativeDeleteSeconds_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.banMember("123", "user1", "-1", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("deleteMessageSeconds must be between");
    }

    @Test
    void timeoutMember_nullDuration_throws() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);

        assertThatThrownBy(() -> service.timeoutMember("123", "user1", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSeconds cannot be null");
    }

    @Test
    void timeoutMember_zeroDuration_throws() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);

        assertThatThrownBy(() -> service.timeoutMember("123", "user1", "0", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSeconds must be between 1 and 2419200");
    }

    @Test
    void timeoutMember_exceedsMax_throws() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);

        assertThatThrownBy(() -> service.timeoutMember("123", "user1", "9999999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("durationSeconds must be between 1 and 2419200");
    }

    @Test
    void timeoutMember_happyPath() {
        CacheRestAction<Member> retrieveAction = mock(CacheRestAction.class);
        AuditableRestAction<Void> timeoutAction = mock(AuditableRestAction.class);
        User user = mock(User.class);

        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveMemberById("user1")).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(member);
        when(guild.timeoutFor(any(Member.class), any())).thenReturn(timeoutAction);
        when(timeoutAction.reason(null)).thenReturn(timeoutAction);
        when(timeoutAction.complete()).thenReturn(null);
        when(member.getUser()).thenReturn(user);
        when(user.getName()).thenReturn("noisyuser");

        String result = service.timeoutMember("123", "user1", "3600", null);

        assertThat(result).contains("Successfully timed out user");
        assertThat(result).contains("noisyuser");
        assertThat(result).contains("3600 seconds");
    }

    @Test
    void getBans_emptyList() {
        BanPaginationAction retrieveAction = mock(BanPaginationAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.retrieveBanList()).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(Collections.emptyList());

        String result = service.getBans("123", null);

        assertThat(result).contains("No banned users found");
    }

    @Test
    void getBans_invalidLimit_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.getBans("123", "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit must be a positive integer");
    }

    @Test
    void getBans_usesDefaultGuildId() {
        ReflectionTestUtils.setField(service, "defaultGuildId", "999");
        BanPaginationAction retrieveAction = mock(BanPaginationAction.class);
        when(jda.getGuildById("999")).thenReturn(guild);
        when(guild.retrieveBanList()).thenReturn(retrieveAction);
        when(retrieveAction.complete()).thenReturn(Collections.emptyList());

        String result = service.getBans(null, null);

        assertThat(result).contains("No banned users found");
    }
}
