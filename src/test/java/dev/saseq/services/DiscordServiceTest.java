package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.requests.restaction.CacheRestAction;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DiscordServiceTest {

    @Mock private JDA jda;
    @Mock private Guild guild;

    private DiscordService service;

    @BeforeEach
    void setUp() {
        service = new DiscordService(jda);
        ReflectionTestUtils.setField(service, "defaultGuildId", "");
    }

    @Test
    void getServerInfo_nullGuildId_throws() {
        assertThatThrownBy(() -> service.getServerInfo(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be null");
    }

    @Test
    void getServerInfo_guildNotFound_throws() {
        when(jda.getGuildById("123")).thenReturn(null);

        assertThatThrownBy(() -> service.getServerInfo("123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("not found");
    }

    @Test
    void getServerInfo_happyPath() {
        Member owner = mock(Member.class);
        User ownerUser = mock(User.class);
        CacheRestAction<Member> retrieveOwner = mock(CacheRestAction.class);

        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getName()).thenReturn("Test Server");
        when(guild.getId()).thenReturn("123");
        when(guild.retrieveOwner()).thenReturn(retrieveOwner);
        when(retrieveOwner.complete()).thenReturn(owner);
        when(owner.getUser()).thenReturn(ownerUser);
        when(ownerUser.getName()).thenReturn("owner_user");
        when(guild.getMemberCount()).thenReturn(42);
        when(guild.getTextChannels()).thenReturn(List.of(mock(TextChannel.class), mock(TextChannel.class)));
        when(guild.getVoiceChannels()).thenReturn(List.of(mock(VoiceChannel.class)));
        when(guild.getCategories()).thenReturn(List.of(mock(Category.class)));
        when(guild.getTimeCreated()).thenReturn(OffsetDateTime.parse("2024-01-15T10:00:00Z"));
        when(guild.getBoostCount()).thenReturn(5);
        when(guild.getBoostTier()).thenReturn(Guild.BoostTier.TIER_1);

        String result = service.getServerInfo("123");

        assertThat(result).contains("Test Server");
        assertThat(result).contains("owner_user");
        assertThat(result).contains("42");
        assertThat(result).contains("TIER_1");
    }

    @Test
    void getServerInfo_usesDefaultGuildId() {
        ReflectionTestUtils.setField(service, "defaultGuildId", "999");

        Member owner = mock(Member.class);
        User ownerUser = mock(User.class);
        CacheRestAction<Member> retrieveOwner = mock(CacheRestAction.class);

        when(jda.getGuildById("999")).thenReturn(guild);
        when(guild.getName()).thenReturn("Default Server");
        when(guild.getId()).thenReturn("999");
        when(guild.retrieveOwner()).thenReturn(retrieveOwner);
        when(retrieveOwner.complete()).thenReturn(owner);
        when(owner.getUser()).thenReturn(ownerUser);
        when(ownerUser.getName()).thenReturn("admin");
        when(guild.getMemberCount()).thenReturn(10);
        when(guild.getTextChannels()).thenReturn(List.of());
        when(guild.getVoiceChannels()).thenReturn(List.of());
        when(guild.getCategories()).thenReturn(List.of());
        when(guild.getTimeCreated()).thenReturn(OffsetDateTime.parse("2024-01-01T00:00:00Z"));
        when(guild.getBoostCount()).thenReturn(0);
        when(guild.getBoostTier()).thenReturn(Guild.BoostTier.NONE);

        String result = service.getServerInfo(null);

        assertThat(result).contains("Default Server");
    }
}
