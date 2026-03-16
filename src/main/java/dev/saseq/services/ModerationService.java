package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.UserSnowflake;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

@Service
public class ModerationService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ModerationService(JDA jda) {
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

    @Tool(name = "kick_member", description = "Kicks a member from the server. The user can rejoin with a new invite.")
    public String kickMember(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user to kick") String userId,
            @ToolParam(description = "Reason for kicking (visible in audit log)", required = false) String reason) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        try {
            guild.kick(member).reason(reason).complete();
            return String.format("Successfully kicked user **%s** (ID: %s).%s",
                    member.getUser().getName(), userId,
                    reason != null ? " Reason: " + reason : "");
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot kick this user - they have a higher or equal role than the bot");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to kick members");
        }
    }

    @Tool(name = "ban_member", description = "Bans a user from the server, preventing them from rejoining. Optionally deletes their recent messages.")
    public String banMember(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user to ban") String userId,
            @ToolParam(description = "Duration in seconds for which to delete the user's messages (max 604800 = 7 days). 0 = no deletion.", required = false) String deleteMessageSeconds,
            @ToolParam(description = "Reason for the ban (visible in audit log)", required = false) String reason) {

        Guild guild = getGuild(guildId);
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }

        int deleteSeconds = 0;
        if (deleteMessageSeconds != null && !deleteMessageSeconds.isEmpty()) {
            deleteSeconds = Integer.parseInt(deleteMessageSeconds);
            if (deleteSeconds < 0 || deleteSeconds > 604800) {
                throw new IllegalArgumentException("deleteMessageSeconds must be between 0 and 604800 (7 days)");
            }
        }

        try {
            guild.ban(UserSnowflake.fromId(userId), deleteSeconds, TimeUnit.SECONDS)
                    .reason(reason).complete();
            return String.format("Successfully banned user ID **%s**.%s",
                    userId, reason != null ? " Reason: " + reason : "");
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot ban this user - they have a higher or equal role than the bot");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to ban members");
        }
    }

    @Tool(name = "unban_member", description = "Removes a ban from a user, allowing them to rejoin the server.")
    public String unbanMember(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user to unban") String userId,
            @ToolParam(description = "Reason for removing the ban (visible in audit log)", required = false) String reason) {

        Guild guild = getGuild(guildId);
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }

        try {
            guild.unban(UserSnowflake.fromId(userId)).reason(reason).complete();
            return String.format("Successfully unbanned user ID **%s**.%s",
                    userId, reason != null ? " Reason: " + reason : "");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to unban members");
        } catch (ErrorResponseException e) {
            throw new IllegalArgumentException("User is not banned or could not be found");
        }
    }

    @Tool(name = "timeout_member", description = "Disables communication for a member for a specified duration (Timeout/Mute). Max 28 days.")
    public String timeoutMember(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the member to timeout") String userId,
            @ToolParam(description = "Duration of the timeout in seconds (max 2419200 = 28 days)") String durationSeconds,
            @ToolParam(description = "Reason for the timeout (visible in audit log)", required = false) String reason) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        if (durationSeconds == null || durationSeconds.isEmpty()) {
            throw new IllegalArgumentException("durationSeconds cannot be null");
        }
        long duration = Long.parseLong(durationSeconds);
        if (duration <= 0 || duration > 2419200) {
            throw new IllegalArgumentException("durationSeconds must be between 1 and 2419200 (28 days)");
        }

        try {
            guild.timeoutFor(member, Duration.ofSeconds(duration)).reason(reason).complete();
            return String.format("Successfully timed out user **%s** (ID: %s) for %d seconds.%s",
                    member.getUser().getName(), userId, duration,
                    reason != null ? " Reason: " + reason : "");
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot timeout this user - they have a higher or equal role than the bot");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to timeout members");
        }
    }

    @Tool(name = "remove_timeout", description = "Removes a timeout (unmute) from a member before it expires.")
    public String removeTimeout(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the member to remove timeout from") String userId,
            @ToolParam(description = "Reason for removing the timeout (visible in audit log)", required = false) String reason) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        try {
            guild.removeTimeout(member).reason(reason).complete();
            return String.format("Successfully removed timeout from user **%s** (ID: %s).%s",
                    member.getUser().getName(), userId,
                    reason != null ? " Reason: " + reason : "");
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot remove timeout - user has a higher or equal role than the bot");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to moderate members");
        }
    }

    @Tool(name = "set_nickname", description = "Changes a member's nickname on the server. Pass empty or null nick to reset to original username.")
    public String setNickname(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user") String userId,
            @ToolParam(description = "New nickname. Empty or null to reset to original username.", required = false) String nick,
            @ToolParam(description = "Reason for the nickname change (visible in audit log)", required = false) String reason) {

        Guild guild = getGuild(guildId);
        Member member = retrieveMember(guild, userId);

        String effectiveNick = (nick == null || nick.isEmpty()) ? null : nick;

        try {
            guild.modifyNickname(member, effectiveNick).reason(reason).complete();
            return String.format("Successfully %s nickname of user **%s** (ID: %s).%s",
                    effectiveNick == null ? "reset" : "set",
                    member.getUser().getName(), userId,
                    reason != null ? " Reason: " + reason : "");
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot change nickname - user has a higher or equal role than the bot");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to change nicknames");
        }
    }

    @Tool(name = "get_bans", description = "Returns a list of banned users on the server with ban reasons.")
    public String getBans(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Maximum number of results to return (default 50)", required = false) String limit) {

        Guild guild = getGuild(guildId);

        int maxResults = 50;
        if (limit != null && !limit.isEmpty()) {
            maxResults = Integer.parseInt(limit);
            if (maxResults <= 0) {
                throw new IllegalArgumentException("limit must be a positive integer");
            }
        }

        try {
            List<Guild.Ban> bans = guild.retrieveBanList().complete();

            if (bans.isEmpty()) {
                return "No banned users found on this server.";
            }

            List<Guild.Ban> limited = bans.stream().limit(maxResults).toList();

            return "Retrieved " + limited.size() + " of " + bans.size() + " bans:\n" +
                    limited.stream()
                            .map(ban -> String.format("- **%s** (ID: %s) — Reason: %s",
                                    ban.getUser().getName(),
                                    ban.getUser().getId(),
                                    ban.getReason() != null ? ban.getReason() : "No reason provided"))
                            .collect(Collectors.joining("\n"));
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to view ban list");
        }
    }
}
