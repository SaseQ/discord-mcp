package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.Role;
import net.dv8tion.jda.api.exceptions.ErrorResponseException;
import net.dv8tion.jda.api.exceptions.HierarchyException;
import net.dv8tion.jda.api.exceptions.InsufficientPermissionException;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class RoleService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public RoleService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    /**
     * Retrieves a list of all roles on the server with their details.
     *
     * @param guildId Optional ID of the Discord server (guild). If not provided, the default server will be used.
     * @return A formatted string containing all roles with their ID, name, color, position, and permissions.
     */
    @Tool(name = "list_roles", description = "Returns a list of all roles on the server along with their ID, names, colors, positions, and permissions")
    public String listRoles(@ToolParam(description = "Discord server ID", required = false) String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        List<Role> roles = guild.getRoles();
        if (roles.isEmpty()) {
            return "No roles found on this server.";
        }

        return "Retrieved " + roles.size() + " roles:\n" +
                roles.stream()
                        .map(role -> String.format(
                                "- **%s** (ID: %s)\n" +
                                        "  • Color: %s (RGB: %d)\n" +
                                        "  • Position: %d\n" +
                                        "  • Hoisted: %s\n" +
                                        "  • Mentionable: %s\n" +
                                        "  • Permissions: %s",
                                role.getName(),
                                role.getId(),
                                role.getColor() != null ? "#" + Integer.toHexString(role.getColor().getRGB()).substring(2).toUpperCase() : "None",
                                role.getColorRaw(),
                                role.getPosition(),
                                role.isHoisted(),
                                role.isMentionable(),
                                role.getPermissionsRaw()
                        ))
                        .collect(Collectors.joining("\n"));
    }

    /**
     * Creates a new role on the server with specified parameters.
     *
     * @param guildId      ID of the Discord server.
     * @param name         Name of the new role.
     * @param color        Optional color value as integer (e.g., 16711680 for red). Default is 0.
     * @param hoist        Optional whether the role should be displayed separately. Default is false.
     * @param mentionable  Optional whether the role can be mentioned. Default is false.
     * @param permissions  Optional permissions bitfield value as string. Default is 0.
     * @return A confirmation message with details of the created role.
     */
    @Tool(name = "create_role", description = "Creates a new role on the server with specified parameters")
    public String createRole(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Name of the new role") String name,
            @ToolParam(description = "Color value as integer (e.g., 16711680 for red). Default is 0", required = false) String color,
            @ToolParam(description = "Whether the role should be displayed separately in the sidebar. Default is false", required = false) String hoist,
            @ToolParam(description = "Whether the role should be mentionable. Default is false", required = false) String mentionable,
            @ToolParam(description = "Permissions bitfield for the role as string. Default is 0", required = false) String permissions) {

        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (name == null || name.isEmpty()) {
            throw new IllegalArgumentException("name cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        try {
            // Parse optional parameters
            int colorValue = (color != null && !color.isEmpty()) ? Integer.parseInt(color) : 0;
            boolean hoistValue = hoist != null && !hoist.isEmpty() && Boolean.parseBoolean(hoist);
            boolean mentionableValue = mentionable != null && !mentionable.isEmpty() && Boolean.parseBoolean(mentionable);
            long permissionsValue = (permissions != null && !permissions.isEmpty()) ? Long.parseLong(permissions) : 0L;

            // Create role
            Role newRole = guild.createRole()
                    .setName(name)
                    .setColor(colorValue != 0 ? new Color(colorValue) : null)
                    .setHoisted(hoistValue)
                    .setMentionable(mentionableValue)
                    .setPermissions(permissionsValue)
                    .complete();

            return String.format(
                    "Successfully created role: **%s** (ID: %s)\n" +
                            "• Color: %d\n" +
                            "• Hoisted: %s\n" +
                            "• Mentionable: %s\n" +
                            "• Permissions: %s",
                    newRole.getName(),
                    newRole.getId(),
                    newRole.getColorRaw(),
                    newRole.isHoisted(),
                    newRole.isMentionable(),
                    newRole.getPermissionsRaw()
            );
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to create roles on this server");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format for color or permissions parameter");
        }
    }

    /**
     * Modifies an existing role on the server. All parameters except guild_id and role_id are optional.
     *
     * @param guildId      ID of the Discord server.
     * @param roleId       ID of the role to edit.
     * @param name         Optional new name for the role.
     * @param color        Optional new color value as integer.
     * @param hoist        Optional new hoist setting.
     * @param mentionable  Optional new mentionable setting.
     * @param permissions  Optional new permissions bitfield value.
     * @return A confirmation message with updated role details.
     */
    @Tool(name = "edit_role", description = "Updates settings of an existing role. All parameters except guild_id and role_id are optional")
    public String editRole(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the role to edit") String roleId,
            @ToolParam(description = "New name for the role", required = false) String name,
            @ToolParam(description = "New color value as integer", required = false) String color,
            @ToolParam(description = "New hoist setting", required = false) String hoist,
            @ToolParam(description = "New mentionable setting", required = false) String mentionable,
            @ToolParam(description = "New permissions bitfield as string", required = false) String permissions) {

        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (roleId == null || roleId.isEmpty()) {
            throw new IllegalArgumentException("roleId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        Role role = guild.getRoleById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found by roleId");
        }

        // Check if role is @everyone
        if (role.isPublicRole()) {
            throw new IllegalArgumentException("Cannot edit the @everyone role directly. This operation is risky and restricted.");
        }

        try {
            var roleManager = role.getManager();

            // Apply changes only for provided parameters
            if (name != null && !name.isEmpty()) {
                roleManager.setName(name);
            }
            if (color != null && !color.isEmpty()) {
                int colorValue = Integer.parseInt(color);
                roleManager.setColor(colorValue != 0 ? new Color(colorValue) : null);
            }
            if (hoist != null && !hoist.isEmpty()) {
                roleManager.setHoisted(Boolean.parseBoolean(hoist));
            }
            if (mentionable != null && !mentionable.isEmpty()) {
                roleManager.setMentionable(Boolean.parseBoolean(mentionable));
            }
            if (permissions != null && !permissions.isEmpty()) {
                long permissionsValue = Long.parseLong(permissions);
                roleManager.setPermissions(permissionsValue);
            }

            roleManager.complete();

            return String.format(
                    "Successfully updated role: **%s** (ID: %s)\n" +
                            "• Color: %d\n" +
                            "• Hoisted: %s\n" +
                            "• Mentionable: %s\n" +
                            "• Permissions: %s",
                    role.getName(),
                    role.getId(),
                    role.getColorRaw(),
                    role.isHoisted(),
                    role.isMentionable(),
                    role.getPermissionsRaw()
            );
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot manage this role - it is higher in the hierarchy than the bot's highest role");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage roles on this server");
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid number format for color or permissions parameter");
        }
    }

    /**
     * Permanently deletes a role from the server.
     *
     * @param guildId ID of the Discord server.
     * @param roleId  ID of the role to delete.
     * @return A confirmation message with the deleted role's name.
     */
    @Tool(name = "delete_role", description = "Permanently deletes a role from the server")
    public String deleteRole(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the role to delete") String roleId) {

        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (roleId == null || roleId.isEmpty()) {
            throw new IllegalArgumentException("roleId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        Role role = guild.getRoleById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found by roleId");
        }

        // Check if role is @everyone
        if (role.isPublicRole()) {
            throw new IllegalArgumentException("Cannot delete the @everyone role");
        }

        String roleName = role.getName();

        try {
            role.delete().complete();
            return "Successfully deleted role: **" + roleName + "** (ID: " + roleId + ")";
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot delete this role - it is higher in the hierarchy than the bot's highest role");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage roles on this server");
        }
    }

    /**
     * Assigns a specified role to a user.
     *
     * @param guildId ID of the Discord server.
     * @param userId  ID of the user who should receive the role.
     * @param roleId  ID of the role to assign.
     * @return A confirmation message with user and role details.
     */
    @Tool(name = "assign_role", description = "Assigns a specified role to a user")
    public String assignRole(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user to receive the role") String userId,
            @ToolParam(description = "ID of the role to assign") String roleId) {

        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (roleId == null || roleId.isEmpty()) {
            throw new IllegalArgumentException("roleId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        Member member;
        try {
            member = guild.retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            throw new IllegalArgumentException("User not found in this server by userId");
        }

        Role role = guild.getRoleById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found by roleId");
        }

        // Check if role is @everyone
        if (role.isPublicRole()) {
            throw new IllegalArgumentException("Cannot assign the @everyone role - all members have it by default");
        }

        try {
            guild.addRoleToMember(member, role).complete();
            return String.format(
                    "Successfully assigned role **%s** to user **%s** (ID: %s)",
                    role.getName(),
                    member.getUser().getName(),
                    member.getId()
            );
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot assign this role - it is higher in the hierarchy than the bot's highest role");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage roles on this server");
        }
    }

    /**
     * Removes a specified role from a user.
     *
     * @param guildId ID of the Discord server.
     * @param userId  ID of the user from whom to remove the role.
     * @param roleId  ID of the role to remove.
     * @return A confirmation message with user and role details.
     */
    @Tool(name = "remove_role", description = "Removes a specified role from a user")
    public String removeRole(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the user from whom to remove the role") String userId,
            @ToolParam(description = "ID of the role to remove") String roleId) {

        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        if (userId == null || userId.isEmpty()) {
            throw new IllegalArgumentException("userId cannot be null");
        }
        if (roleId == null || roleId.isEmpty()) {
            throw new IllegalArgumentException("roleId cannot be null");
        }

        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }

        Member member;
        try {
            member = guild.retrieveMemberById(userId).complete();
        } catch (ErrorResponseException e) {
            throw new IllegalArgumentException("User not found in this server by userId");
        }

        Role role = guild.getRoleById(roleId);
        if (role == null) {
            throw new IllegalArgumentException("Role not found by roleId");
        }

        // Check if role is @everyone
        if (role.isPublicRole()) {
            throw new IllegalArgumentException("Cannot remove the @everyone role - all members have it by default");
        }

        try {
            guild.removeRoleFromMember(member, role).complete();
            return String.format(
                    "Successfully removed role **%s** from user **%s** (ID: %s)",
                    role.getName(),
                    member.getUser().getName(),
                    member.getId()
            );
        } catch (HierarchyException e) {
            throw new IllegalArgumentException("Cannot remove this role - it is higher in the hierarchy than the bot's highest role");
        } catch (InsufficientPermissionException e) {
            throw new IllegalArgumentException("Bot lacks permission to manage roles on this server");
        }
    }
}
