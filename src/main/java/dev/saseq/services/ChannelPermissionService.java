package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.Permission;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.IPermissionHolder;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.PermissionOverride;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.entities.channel.attribute.IPermissionContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class ChannelPermissionService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ChannelPermissionService(JDA jda) {
        this.jda = jda;
    }

    private Guild getGuild(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty())
            guildId = defaultGuildId;
        if (guildId == null || guildId.isEmpty()) throw new IllegalArgumentException("guildId cannot be null");
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) throw new IllegalArgumentException("Discord server not found by guildId");
        return guild;
    }

    private IPermissionContainer getPermissionContainer(Guild guild, String channelId) {
        if (channelId == null || channelId.isEmpty()) throw new IllegalArgumentException("channelId cannot be null");
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) throw new IllegalArgumentException("Channel not found by channelId");
        if (!(channel instanceof IPermissionContainer container))
            throw new IllegalArgumentException("Channel type does not support permission overwrites (threads are not supported)");
        return container;
    }

    private long parsePermissions(String raw, String names) {
        if (raw != null && !raw.isEmpty() && names != null && !names.isEmpty())
            throw new IllegalArgumentException("Cannot specify both raw bitfield and permission names for the same field");
        if (raw != null && !raw.isEmpty()) {
            try { return Long.parseLong(raw); }
            catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid permission bitfield: " + raw); }
        }
        if (names != null && !names.isEmpty()) {
            long result = 0;
            for (String name : names.split(",")) {
                try { result |= Permission.valueOf(name.trim()).getRawValue(); }
                catch (IllegalArgumentException e) { throw new IllegalArgumentException("Invalid permission name: " + name.trim()); }
            }
            return result;
        }
        return 0;
    }

    private String formatPermissions(long raw) {
        if (raw == 0) return "none";
        return Permission.getPermissions(raw).stream().map(Permission::getName).collect(Collectors.joining(", "));
    }

    @Tool(name = "list_channel_permission_overwrites", description = "Returns all permission overwrites for a channel with role/member breakdown and allow/deny details")
    public String listChannelPermissionOverwrites(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel ID") String channelId) {

        IPermissionContainer container = getPermissionContainer(getGuild(guildId), channelId);
        List<PermissionOverride> overrides = container.getPermissionOverrides();
        if (overrides.isEmpty()) return "No permission overwrites found for this channel.";

        return "Retrieved " + overrides.size() + " permission overwrites:\n" +
                overrides.stream().map(o -> {
                    String type = o.isRoleOverride() ? "Role" : "Member";
                    String name = o.isRoleOverride()
                            ? (o.getRole() != null ? o.getRole().getName() : "Unknown Role")
                            : (o.getMember() != null ? o.getMember().getUser().getName() : "Unknown Member");
                    return String.format("- **%s: %s** (ID: %s)\n  • Allow: %d (%s)\n  • Deny: %d (%s)",
                            type, name, o.getId(),
                            o.getAllowedRaw(), formatPermissions(o.getAllowedRaw()),
                            o.getDeniedRaw(), formatPermissions(o.getDeniedRaw()));
                }).collect(Collectors.joining("\n"));
    }

    private String upsertPermissions(IPermissionContainer container, IPermissionHolder holder,
                                     String targetType, String targetName, String targetId,
                                     String allowRaw, String denyRaw, String allowPerms, String denyPerms, String reason) {
        long allow = parsePermissions(allowRaw, allowPerms);
        long deny = parsePermissions(denyRaw, denyPerms);
        if (allow == 0 && deny == 0)
            throw new IllegalArgumentException("Must specify at least one allow or deny permission");
        if ((allow & deny) != 0)
            throw new IllegalArgumentException("allow and deny cannot contain the same permission bits");

        try {
            var action = container.upsertPermissionOverride(holder).setPermissions(allow, deny);
            if (reason != null && !reason.isEmpty()) action.reason(reason);
            action.complete();
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot manage overwrite target due to role hierarchy");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage channel permissions");
        }

        return String.format("Successfully set permission overwrite for %s **%s** (ID: %s):\n• Allow: %d (%s)\n• Deny: %d (%s)",
                targetType, targetName, targetId, allow, formatPermissions(allow), deny, formatPermissions(deny));
    }

    @Tool(name = "upsert_role_channel_permissions", description = "Creates or updates permission overwrite for a role on a channel")
    public String upsertRoleChannelPermissions(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel ID") String channelId,
            @ToolParam(description = "Role ID") String roleId,
            @ToolParam(description = "Allow permissions raw bitfield", required = false) String allowRaw,
            @ToolParam(description = "Deny permissions raw bitfield", required = false) String denyRaw,
            @ToolParam(description = "Allow permissions as CSV of names (e.g. VIEW_CHANNEL,MESSAGE_SEND)", required = false) String allowPermissions,
            @ToolParam(description = "Deny permissions as CSV of names (e.g. MESSAGE_SEND,MANAGE_MESSAGES)", required = false) String denyPermissions,
            @ToolParam(description = "Reason for audit log", required = false) String reason) {

        Guild guild = getGuild(guildId);
        IPermissionContainer container = getPermissionContainer(guild, channelId);
        if (roleId == null || roleId.isEmpty()) throw new IllegalArgumentException("roleId cannot be null");
        Role role = guild.getRoleById(roleId);
        if (role == null) throw new IllegalArgumentException("Role not found by roleId");

        return upsertPermissions(container, role, "role", role.getName(), role.getId(),
                allowRaw, denyRaw, allowPermissions, denyPermissions, reason);
    }

    @Tool(name = "upsert_member_channel_permissions", description = "Creates or updates permission overwrite for a member on a channel")
    public String upsertMemberChannelPermissions(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel ID") String channelId,
            @ToolParam(description = "User ID") String userId,
            @ToolParam(description = "Allow permissions raw bitfield", required = false) String allowRaw,
            @ToolParam(description = "Deny permissions raw bitfield", required = false) String denyRaw,
            @ToolParam(description = "Allow permissions as CSV of names (e.g. VIEW_CHANNEL,VOICE_CONNECT)", required = false) String allowPermissions,
            @ToolParam(description = "Deny permissions as CSV of names (e.g. MESSAGE_SEND,VOICE_SPEAK)", required = false) String denyPermissions,
            @ToolParam(description = "Reason for audit log", required = false) String reason) {

        Guild guild = getGuild(guildId);
        IPermissionContainer container = getPermissionContainer(guild, channelId);
        if (userId == null || userId.isEmpty()) throw new IllegalArgumentException("userId cannot be null");
        Member member;
        try { member = guild.retrieveMemberById(userId).complete(); }
        catch (ErrorResponseException e) { throw new IllegalArgumentException("User not found in this server by userId"); }

        return upsertPermissions(container, member, "member", member.getUser().getName(), member.getId(),
                allowRaw, denyRaw, allowPermissions, denyPermissions, reason);
    }

    @Tool(name = "delete_channel_permission_overwrite", description = "Deletes a permission overwrite for a role or member from a channel")
    public String deleteChannelPermissionOverwrite(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel ID") String channelId,
            @ToolParam(description = "Target type: 'role' or 'member'") String targetType,
            @ToolParam(description = "Target role or user ID") String targetId,
            @ToolParam(description = "Reason for audit log", required = false) String reason) {

        Guild guild = getGuild(guildId);
        IPermissionContainer container = getPermissionContainer(guild, channelId);
        if (targetType == null || targetType.isEmpty()) throw new IllegalArgumentException("targetType cannot be null");
        if (targetId == null || targetId.isEmpty()) throw new IllegalArgumentException("targetId cannot be null");
        if (!targetType.equals("role") && !targetType.equals("member"))
            throw new IllegalArgumentException("targetType must be 'role' or 'member'");

        PermissionOverride override = container.getPermissionOverrides().stream()
                .filter(o -> o.getId().equals(targetId))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException("No permission overwrite found for target ID: " + targetId));

        if (targetType.equals("role") && !override.isRoleOverride())
            throw new IllegalArgumentException("Target ID refers to a member overwrite, not a role overwrite");
        if (targetType.equals("member") && !override.isMemberOverride())
            throw new IllegalArgumentException("Target ID refers to a role overwrite, not a member overwrite");

        String targetName = override.isRoleOverride()
                ? (override.getRole() != null ? override.getRole().getName() : "Unknown Role")
                : (override.getMember() != null ? override.getMember().getUser().getName() : "Unknown Member");

        try {
            var action = override.delete();
            if (reason != null && !reason.isEmpty()) action.reason(reason);
            action.complete();
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage channel permissions");
        }

        return String.format("Successfully deleted permission overwrite for %s **%s** (ID: %s) from channel", targetType, targetName, targetId);
    }

    /**
     * Makes a channel read-only for the @everyone role, or restores the default, by changing only the
     * Send Messages (MESSAGE_SEND) bit of the @everyone permission overwrite. Every other allow/deny bit
     * already present in that overwrite is left untouched, unlike {@code upsert_role_channel_permissions},
     * which replaces the whole overwrite.
     *
     * @param guildId   Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @param channelId The ID of the channel to modify.
     * @param readonly  "true" to deny Send Messages for @everyone, "false" to clear the Send Messages bit so the channel's default applies.
     * @param reason    Optional reason for the audit log.
     * @return A confirmation message indicating the channel's new state.
     */
    @Tool(name = "set_channel_readonly", description = "Makes a channel read-only for @everyone by denying Send Messages (true), or resets that permission to the default (false). Only the Send Messages bit of the @everyone overwrite is changed; other overwrite bits are preserved")
    public String setChannelReadonly(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Channel ID") String channelId,
            @ToolParam(description = "true to deny Send Messages for @everyone, false to reset it to the default") String readonly,
            @ToolParam(description = "Reason for audit log", required = false) String reason) {

        Guild guild = getGuild(guildId);
        IPermissionContainer container = getPermissionContainer(guild, channelId);
        if (readonly == null || readonly.isEmpty()) throw new IllegalArgumentException("readonly cannot be null");
        if (!readonly.equalsIgnoreCase("true") && !readonly.equalsIgnoreCase("false"))
            throw new IllegalArgumentException("readonly must be 'true' or 'false'");
        boolean makeReadonly = Boolean.parseBoolean(readonly);

        try {
            var action = container.upsertPermissionOverride(guild.getPublicRole());
            action = makeReadonly ? action.deny(Permission.MESSAGE_SEND) : action.clear(Permission.MESSAGE_SEND);
            if (reason != null && !reason.isEmpty()) action.reason(reason);
            action.complete();
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage channel permissions (" + e.getPermission().getName() + ")");
        }

        String name = container.getName();
        return makeReadonly
                ? "Channel #" + name + " is now read-only for @everyone (Send Messages denied)"
                : "Channel #" + name + " no longer overrides Send Messages for @everyone (default applies)";
    }
}
