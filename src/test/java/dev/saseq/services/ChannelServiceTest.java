package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.ChannelType;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.restaction.ChannelAction;
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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ChannelServiceTest {

    @Mock private JDA jda;
    @Mock private Guild guild;

    private ChannelService service;

    @BeforeEach
    void setUp() {
        service = new ChannelService(jda);
        ReflectionTestUtils.setField(service, "defaultGuildId", "");
    }

    @Test
    void getGuild_nullGuildIdWithDefault_usesDefault() {
        ReflectionTestUtils.setField(service, "defaultGuildId", "999");
        when(jda.getGuildById("999")).thenReturn(guild);
        when(guild.getChannels()).thenReturn(Collections.emptyList());

        assertThatThrownBy(() -> service.listChannels(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No channels found");
    }

    @Test
    void getGuild_nullGuildIdNoDefault_throws() {
        ReflectionTestUtils.setField(service, "defaultGuildId", "");

        assertThatThrownBy(() -> service.listChannels(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("guildId cannot be null");
    }

    @Test
    void getGuild_guildNotFound_throws() {
        when(jda.getGuildById("123")).thenReturn(null);

        assertThatThrownBy(() -> service.listChannels("123"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Discord server not found");
    }

    @Test
    void listChannels_happyPath() {
        GuildChannel channel = mock(GuildChannel.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getChannels()).thenReturn(List.of(channel));
        when(channel.getType()).thenReturn(ChannelType.TEXT);
        when(channel.getName()).thenReturn("general");
        when(channel.getId()).thenReturn("456");

        String result = service.listChannels("123");

        assertThat(result).contains("Retrieved 1 channels");
        assertThat(result).contains("general");
    }

    @Test
    void findChannel_nullName_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.findChannel("123", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelName cannot be null");
    }

    @Test
    void findChannel_noMatch_throws() {
        GuildChannel channel = mock(GuildChannel.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getChannels()).thenReturn(List.of(channel));
        when(channel.getName()).thenReturn("general");

        assertThatThrownBy(() -> service.findChannel("123", "random"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No channels found with name");
    }

    @Test
    void findChannel_singleMatch() {
        GuildChannel channel = mock(GuildChannel.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getChannels()).thenReturn(List.of(channel));
        when(channel.getName()).thenReturn("general");
        when(channel.getType()).thenReturn(ChannelType.TEXT);
        when(channel.getId()).thenReturn("456");

        String result = service.findChannel("123", "general");

        assertThat(result).contains("Retrieved TEXT channel: general");
    }

    @Test
    void deleteChannel_nullChannelId_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.deleteChannel("123", null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("channelId cannot be null");
    }

    @Test
    void deleteChannel_channelNotFound_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.getGuildChannelById("456")).thenReturn(null);

        assertThatThrownBy(() -> service.deleteChannel("123", "456", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Channel not found");
    }

    @Test
    void createTextChannel_nullName_throws() {
        when(jda.getGuildById("123")).thenReturn(guild);

        assertThatThrownBy(() -> service.createTextChannel("123", null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("name cannot be null");
    }

    @Test
    void createTextChannel_happyPath() {
        TextChannel created = mock(TextChannel.class);
        ChannelAction<TextChannel> action = mock(ChannelAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.createTextChannel("test")).thenReturn(action);
        when(action.complete()).thenReturn(created);
        when(created.getName()).thenReturn("test");
        when(created.getId()).thenReturn("789");

        String result = service.createTextChannel("123", "test", null, null, null, null, null);

        assertThat(result).contains("Created text channel: test");
        assertThat(result).contains("789");
    }

    @Test
    void createTextChannel_withCategory() {
        TextChannel created = mock(TextChannel.class);
        Category category = mock(Category.class);
        ChannelAction<TextChannel> action = mock(ChannelAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.createTextChannel("test")).thenReturn(action);
        when(guild.getCategoryById("cat1")).thenReturn(category);
        when(action.setParent(category)).thenReturn(action);
        when(action.complete()).thenReturn(created);
        when(created.getName()).thenReturn("test");
        when(created.getId()).thenReturn("789");

        String result = service.createTextChannel("123", "test", "cat1", null, null, null, null);

        assertThat(result).contains("Created text channel: test");
    }

    @Test
    void createTextChannel_categoryNotFound_throws() {
        ChannelAction<TextChannel> action = mock(ChannelAction.class);
        when(jda.getGuildById("123")).thenReturn(guild);
        when(guild.createTextChannel("test")).thenReturn(action);
        when(guild.getCategoryById("bad")).thenReturn(null);

        assertThatThrownBy(() -> service.createTextChannel("123", "test", "bad", null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Category not found");
    }
}
