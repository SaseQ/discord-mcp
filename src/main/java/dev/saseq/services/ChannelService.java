package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildChannel;
import net.dv8tion.jda.api.entities.channel.middleman.StandardGuildMessageChannel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChannelService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ChannelService(JDA jda) {
        this.jda = jda;
    }

    private Guild getGuild(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            guildId = defaultGuildId;
        }
        if (guildId == null || guildId.isEmpty()) throw new IllegalArgumentException("guildId cannot be null");
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalArgumentException("Discord server not found by guildId");
        return guild;
    }

    @Tool(name = "delete_channel", description = "Delete a channel")
    public String deleteChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                @ToolParam(description = "Discord channel ID") String channelId,
                                @ToolParam(description = "Reason for audit log", required = false) String reason) {
        Guild guild = getGuild(guildId);
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) throw new IllegalArgumentException("Channel not found by channelId");
        var action = channel.delete();
        if (reason != null && !reason.isEmpty()) action.reason(reason);
        action.queue();
        return "Deleted " + channel.getType().name() + " channel: " + channel.getName();
    }

    @Tool(name = "create_text_channel", description = "Create a new text channel")
    public String createTextChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                    @ToolParam(description = "Channel name") String name,
                                    @ToolParam(description = "Category ID", required = false) String categoryId,
                                    @ToolParam(description = "Channel topic", required = false) String topic,
                                    @ToolParam(description = "Whether channel is NSFW", required = false) String nsfw,
                                    @ToolParam(description = "Slowmode in seconds (0-21600)", required = false) String slowmode,
                                    @ToolParam(description = "Position in channel list", required = false) String position) {
        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be null");
        var action = guild.createTextChannel(name);
        if (categoryId != null && !categoryId.isEmpty()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
            action.setParent(category);
        }
        if (topic != null && !topic.isEmpty()) action.setTopic(topic);
        if (nsfw != null && !nsfw.isEmpty()) action.setNSFW(Boolean.parseBoolean(nsfw));
        if (slowmode != null && !slowmode.isEmpty()) action.setSlowmode(Integer.parseInt(slowmode));
        if (position != null && !position.isEmpty()) action.setPosition(Integer.parseInt(position));
        TextChannel textChannel = action.complete();
        return "Created text channel: " + textChannel.getName() + " (ID: " + textChannel.getId() + ")";
    }

    @Tool(name = "find_channel", description = "Find a channel type and ID using name and server ID")
    public String findChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                              @ToolParam(description = "Discord channel name") String channelName) {
        Guild guild = getGuild(guildId);
        if (channelName == null || channelName.isEmpty()) throw new IllegalArgumentException("channelName cannot be null");
        List<GuildChannel> channels = guild.getChannels();
        if (channels.isEmpty()) throw new IllegalArgumentException("No channels found by guildId");
        List<GuildChannel> filteredChannels = channels.stream()
                .filter(c -> c.getName().equalsIgnoreCase(channelName)).toList();
        if (filteredChannels.isEmpty()) throw new IllegalArgumentException("No channels found with name " + channelName);
        if (filteredChannels.size() > 1) {
            return "Retrieved " + filteredChannels.size() + " channels:\n" +
                    filteredChannels.stream()
                            .map(c -> "- " + c.getType().name() + " channel: " + c.getName() + " (ID: " + c.getId() + ")")
                            .collect(Collectors.joining("\n"));
        }
        GuildChannel channel = filteredChannels.get(0);
        return "Retrieved " + channel.getType().name() + " channel: " + channel.getName() + " (ID: " + channel.getId() + ")";
    }

    @Tool(name = "list_channels", description = "List of all channels")
    public String listChannels(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        Guild guild = getGuild(guildId);
        List<GuildChannel> channels = guild.getChannels();
        if (channels.isEmpty()) throw new IllegalArgumentException("No channels found by guildId");
        return "Retrieved " + channels.size() + " channels:\n" +
                channels.stream()
                        .map(c -> "- " + c.getType().name() + " channel: " + c.getName() + " (ID: " + c.getId() + ")")
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "edit_text_channel", description = "Edit settings of a text channel (name, topic, nsfw, slowmode, category, position)")
    public String editTextChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                  @ToolParam(description = "Channel ID") String channelId,
                                  @ToolParam(description = "New channel name", required = false) String name,
                                  @ToolParam(description = "New channel topic", required = false) String topic,
                                  @ToolParam(description = "Whether channel is NSFW", required = false) String nsfw,
                                  @ToolParam(description = "Slowmode in seconds (0-21600)", required = false) String slowmode,
                                  @ToolParam(description = "Category ID (empty to remove from category)", required = false) String categoryId,
                                  @ToolParam(description = "Position in channel list", required = false) String position,
                                  @ToolParam(description = "Reason for audit log", required = false) String reason) {
        Guild guild = getGuild(guildId);
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        GuildChannel guildChannel = guild.getGuildChannelById(channelId);
        if (guildChannel instanceof TextChannel channel) {
            var manager = channel.getManager();
            if (name != null && !name.isEmpty()) manager.setName(name);
            if (topic != null) manager.setTopic(topic);
            if (nsfw != null && !nsfw.isEmpty()) manager.setNSFW(Boolean.parseBoolean(nsfw));
            if (slowmode != null && !slowmode.isEmpty()) manager.setSlowmode(Integer.parseInt(slowmode));
            if (categoryId != null) {
                if (categoryId.isEmpty()) manager.setParent(null);
                else {
                    Category category = guild.getCategoryById(categoryId);
                    if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
                    manager.setParent(category);
                }
            }
            if (position != null && !position.isEmpty()) manager.setPosition(Integer.parseInt(position));
            if (reason != null && !reason.isEmpty()) manager.reason(reason);
            manager.complete();
            return "Updated text channel: " + channel.getName() + " (ID: " + channelId + ")";
        }
        if (guildChannel instanceof StandardGuildMessageChannel channel) {
            if (slowmode != null && !slowmode.isEmpty()) {
                throw new IllegalArgumentException("slowmode is supported only for text channels");
            }
            var manager = channel.getManager();
            if (name != null && !name.isEmpty()) manager.setName(name);
            if (topic != null) manager.setTopic(topic);
            if (nsfw != null && !nsfw.isEmpty()) manager.setNSFW(Boolean.parseBoolean(nsfw));
            if (categoryId != null) {
                if (categoryId.isEmpty()) manager.setParent(null);
                else {
                    Category category = guild.getCategoryById(categoryId);
                    if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
                    manager.setParent(category);
                }
            }
            if (position != null && !position.isEmpty()) manager.setPosition(Integer.parseInt(position));
            if (reason != null && !reason.isEmpty()) manager.reason(reason);
            manager.complete();
            return "Updated " + channel.getType().name() + " channel: " + channel.getName() + " (ID: " + channelId + ")";
        }
        throw new IllegalArgumentException("Channel not found or is not a standard text/news channel");
    }

    @Tool(name = "get_channel_info", description = "Get detailed information about a channel")
    public String getChannelInfo(@ToolParam(description = "Discord server ID", required = false) String guildId,
                                 @ToolParam(description = "Channel ID") String channelId) {
        Guild guild = getGuild(guildId);
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) throw new IllegalArgumentException("Channel not found by channelId");
        var info = new StringBuilder()
                .append("Type: ").append(channel.getType().name()).append("\n")
                .append("Name: ").append(channel.getName()).append("\n")
                .append("ID: ").append(channel.getId()).append("\n");
        if (channel instanceof TextChannel tc) {
            info.append("Topic: ").append(tc.getTopic() != null ? tc.getTopic() : "none").append("\n")
                    .append("NSFW: ").append(tc.isNSFW()).append("\n")
                    .append("Slowmode: ").append(tc.getSlowmode()).append("s\n")
                    .append("Position: ").append(tc.getPosition()).append("\n");
            if (tc.getParentCategory() != null) {
                info.append("Category: ").append(tc.getParentCategory().getName())
                        .append(" (ID: ").append(tc.getParentCategory().getId()).append(")\n");
            }
            info.append("Created: ").append(tc.getTimeCreated().toLocalDate());
        } else if (channel instanceof VoiceChannel vc) {
            info.append("Bitrate: ").append(vc.getBitrate()).append("\n")
                    .append("User Limit: ").append(vc.getUserLimit()).append("\n")
                    .append("Position: ").append(vc.getPosition()).append("\n");
            if (vc.getParentCategory() != null) {
                info.append("Category: ").append(vc.getParentCategory().getName())
                        .append(" (ID: ").append(vc.getParentCategory().getId()).append(")\n");
            }
            info.append("Created: ").append(vc.getTimeCreated().toLocalDate());
        } else if (channel instanceof Category cat) {
            info.append("Position: ").append(cat.getPosition()).append("\n")
                    .append("Channels: ").append(cat.getChannels().size()).append("\n")
                    .append("Created: ").append(cat.getTimeCreated().toLocalDate());
        } else {
            info.append("Created: ").append(channel.getTimeCreated().toLocalDate());
        }
        return info.toString();
    }

    @Tool(name = "move_channel", description = "Move a channel to another category and/or change its position")
    public String moveChannel(@ToolParam(description = "Discord server ID", required = false) String guildId,
                              @ToolParam(description = "Channel ID") String channelId,
                              @ToolParam(description = "Target category ID (empty to remove from category)", required = false) String categoryId,
                              @ToolParam(description = "New position in channel list", required = false) String position,
                              @ToolParam(description = "Reason for audit log", required = false) String reason) {
        Guild guild = getGuild(guildId);
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        GuildChannel guildChannel = guild.getGuildChannelById(channelId);
        if (!(guildChannel instanceof StandardGuildChannel channel)) {
            throw new IllegalArgumentException("Channel not found or cannot be moved");
        }
        var manager = channel.getManager();
        if (categoryId != null) {
            if (categoryId.isEmpty()) manager.setParent(null);
            else {
                Category category = guild.getCategoryById(categoryId);
                if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
                manager.setParent(category);
            }
        }
        if (position != null && !position.isEmpty()) manager.setPosition(Integer.parseInt(position));
        if (reason != null && !reason.isEmpty()) manager.reason(reason);
        manager.complete();
        return "Moved channel: " + channel.getName() + " (ID: " + channelId + ")";
    }
}
