package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Region;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.GuildVoiceState;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.channel.concrete.Category;
import net.dv8tion.jda.api.entities.channel.concrete.StageChannel;
import net.dv8tion.jda.api.entities.channel.concrete.VoiceChannel;
import net.dv8tion.jda.api.entities.channel.middleman.AudioChannel;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

@Service
public class VoiceChannelService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public VoiceChannelService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild getGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    private Member retrieveMember(Guild guild, String userId) {
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        try {
            return guild.retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            throw new IllegalArgumentException("User not found in this server by userId");
        }
    }

    @Tool(name = "create_voice_channel", description = "Create a new voice channel in a guild")
    public String createVoiceChannel(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel name") String name,
            @ToolParam(description = "Category ID", required = false) String categoryId,
            @ToolParam(description = "Max users (0 = unlimited, max 99)", required = false) String userLimit,
            @ToolParam(description = "Audio bitrate in bits/s (e.g. 64000). Max depends on server boost level", required = false) String bitrate) {

        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null");
        }

        var action = guild.createVoiceChannel(name);
        if (categoryId != null && !categoryId.isEmpty()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
            action.setParent(category);
        }
        if (bitrate != null && !bitrate.isEmpty()) action.setBitrate(Integer.parseInt(bitrate));

        VoiceChannel channel = action.complete();
        if (userLimit != null && !userLimit.isEmpty()) {
            channel.getManager().setUserLimit(Integer.parseInt(userLimit)).complete();
        }
        return "Created voice channel: " + channel.getName() + " (ID: " + channel.getId() + ")";
    }

    @Tool(name = "create_stage_channel", description = "Create a new stage channel for audio events in a guild")
    public String createStageChannel(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel name") String name,
            @ToolParam(description = "Category ID", required = false) String categoryId,
            @ToolParam(description = "Audio bitrate in bits/s (e.g. 64000)", required = false) String bitrate) {

        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null");
        }

        var action = guild.createStageChannel(name);
        if (categoryId != null && !categoryId.isEmpty()) {
            Category category = guild.getCategoryById(categoryId);
            if (category == null) throw new IllegalArgumentException("Category not found by categoryId");
            action.setParent(category);
        }
        if (bitrate != null && !bitrate.isEmpty()) action.setBitrate(Integer.parseInt(bitrate));

        StageChannel channel = action.complete();
        return "Created stage channel: " + channel.getName() + " (ID: " + channel.getId() + ")";
    }

    @Tool(name = "edit_voice_channel", description = "Edit settings of a voice or stage channel (name, bitrate, user limit, region)")
    public String editVoiceChannel(
            @ToolParam(description = "Channel ID") String channelId,
            @ToolParam(description = "New channel name", required = false) String name,
            @ToolParam(description = "New bitrate in bits/s", required = false) String bitrate,
            @ToolParam(description = "New user limit (0 = unlimited)", required = false) String userLimit,
            @ToolParam(description = "Voice region (e.g. 'rotterdam', 'us-east'). Empty for automatic", required = false) String rtcRegion) {

        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }

        VoiceChannel voiceChannel = jda.getVoiceChannelById(channelId);
        if (voiceChannel != null) {
            var manager = voiceChannel.getManager();
            if (name != null && !name.isEmpty()) manager.setName(name);
            if (bitrate != null && !bitrate.isEmpty()) manager.setBitrate(Integer.parseInt(bitrate));
            if (userLimit != null && !userLimit.isEmpty()) manager.setUserLimit(Integer.parseInt(userLimit));
            if (rtcRegion != null) manager.setRegion(rtcRegion.isEmpty() ? Region.AUTOMATIC : Region.fromKey(rtcRegion));
            manager.complete();
            return "Updated voice channel: " + voiceChannel.getName() + " (ID: " + channelId + ")";
        }

        StageChannel stageChannel = jda.getStageChannelById(channelId);
        if (stageChannel != null) {
            var manager = stageChannel.getManager();
            if (name != null && !name.isEmpty()) manager.setName(name);
            if (bitrate != null && !bitrate.isEmpty()) manager.setBitrate(Integer.parseInt(bitrate));
            if (rtcRegion != null) manager.setRegion(rtcRegion.isEmpty() ? Region.AUTOMATIC : Region.fromKey(rtcRegion));
            manager.complete();
            return "Updated stage channel: " + stageChannel.getName() + " (ID: " + channelId + ")";
        }

        throw new IllegalArgumentException("Voice or stage channel not found by channelId");
    }

    @Tool(name = "move_member", description = "Move a member to another voice channel. The member must already be connected to a voice channel.")
    public String moveMember(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user to move") String userId,
            @ToolParam(description = "ID of the target voice channel") String channelId) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        GuildVoiceState voiceState = member.getVoiceState();
        if (voiceState == null || !voiceState.inAudioChannel()) {
            throw new IllegalArgumentException("User is not connected to any voice channel");
        }

        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (!(channel instanceof AudioChannel target)) {
            throw new IllegalArgumentException("Target channel is not a voice or stage channel");
        }

        try {
            guild.moveVoiceMember(member, target).complete();
            return "Moved user " + member.getUser().getName() + " to channel: " + target.getName();
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to move members");
        }
    }

    @Tool(name = "disconnect_member", description = "Disconnect a member from their current voice channel")
    public String disconnectMember(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user to disconnect") String userId) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        GuildVoiceState voiceState = member.getVoiceState();
        if (voiceState == null || !voiceState.inAudioChannel()) {
            throw new IllegalArgumentException("User is not connected to any voice channel");
        }

        try {
            guild.moveVoiceMember(member, null).complete();
            return "Disconnected user " + member.getUser().getName() + " from voice channel";
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to disconnect members");
        }
    }

    @Tool(name = "modify_voice_state", description = "Server mute or deafen a member across all voice channels. The member must be in a voice channel.")
    public String modifyVoiceState(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user") String userId,
            @ToolParam(description = "Whether to server-mute the user's microphone", required = false) String mute,
            @ToolParam(description = "Whether to server-deafen the user's audio", required = false) String deafen) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        GuildVoiceState voiceState = member.getVoiceState();
        if (voiceState == null || !voiceState.inAudioChannel()) {
            throw new IllegalArgumentException("User is not connected to any voice channel");
        }

        if ((mute == null || mute.isEmpty()) && (deafen == null || deafen.isEmpty())) {
            throw new IllegalArgumentException("At least one of 'mute' or 'deafen' must be provided");
        }

        try {
            StringBuilder result = new StringBuilder();
            if (mute != null && !mute.isEmpty()) {
                boolean muteValue = Boolean.parseBoolean(mute);
                guild.mute(member, muteValue).complete();
                result.append(muteValue ? "Muted" : "Unmuted").append(" user ").append(member.getUser().getName());
            }
            if (deafen != null && !deafen.isEmpty()) {
                boolean deafenValue = Boolean.parseBoolean(deafen);
                guild.deafen(member, deafenValue).complete();
                if (!result.isEmpty()) result.append(". ");
                result.append(deafenValue ? "Deafened" : "Undeafened").append(" user ").append(member.getUser().getName());
            }
            return result.toString();
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to mute/deafen members");
        }
    }
}
