package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Invite;
import net.dv8tion.jda.api.entities.channel.attribute.IInviteContainer;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.stream.Collectors;

@Service
public class InviteService {

    private final JDA jda;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public InviteService(JDA jda) {
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

    private String extractInviteCode(String input) {
        if (input == null || input.isEmpty()) {
            throw new IllegalArgumentException("invite_code cannot be null");
        }
        return input
                .replace("https://discord.gg/", "")
                .replace("http://discord.gg/", "")
                .replace("https://discord.com/invite/", "")
                .replace("http://discord.com/invite/", "")
                .trim();
    }

    @Tool(name = "create_invite", description = "Create a new invite link for a specific channel")
    public String createInvite(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the channel the invite directs to") String channelId,
            @ToolParam(description = "Duration in seconds before expiry (0 = never, default 86400 = 24h)", required = false) String maxAge,
            @ToolParam(description = "Max number of uses (0 = unlimited, default 0)", required = false) String maxUses,
            @ToolParam(description = "Whether members get temporary membership (kicked when disconnected unless role assigned)", required = false) String temporary,
            @ToolParam(description = "Force creation of a new unique invite code", required = false) String unique) {

        Guild guild = getGuild(guildId);
        if (channelId == null || channelId.isEmpty()) {
            throw new IllegalArgumentException("channelId cannot be null");
        }
        GuildChannel channel = guild.getGuildChannelById(channelId);
        if (channel == null) {
            throw new IllegalArgumentException("Channel not found by channelId");
        }
        if (!(channel instanceof IInviteContainer inviteChannel)) {
            throw new IllegalArgumentException("This channel type does not support invites");
        }

        var action = inviteChannel.createInvite();
        if (maxAge != null && !maxAge.isEmpty()) action.setMaxAge(Integer.parseInt(maxAge));
        if (maxUses != null && !maxUses.isEmpty()) action.setMaxUses(Integer.parseInt(maxUses));
        if (temporary != null && !temporary.isEmpty()) action.setTemporary(Boolean.parseBoolean(temporary));
        if (unique != null && !unique.isEmpty()) action.setUnique(Boolean.parseBoolean(unique));

        Invite invite = action.complete();
        return "Created invite: https://discord.gg/" + invite.getCode() +
                "\n  • Channel: " + channel.getName() + " (ID: " + channelId + ")" +
                "\n  • Max Age: " + (invite.getMaxAge() == 0 ? "Never expires" : invite.getMaxAge() + "s") +
                "\n  • Max Uses: " + (invite.getMaxUses() == 0 ? "Unlimited" : invite.getMaxUses()) +
                "\n  • Temporary: " + invite.isTemporary();
    }

    @Tool(name = "list_invites", description = "List all active invites on the server with their statistics")
    public String listInvites(
            @ToolParam(description = "Discord server ID", required = false) String guildId) {

        Guild guild = getGuild(guildId);
        List<Invite> invites = guild.retrieveInvites().complete();

        if (invites.isEmpty()) {
            return "No active invites found on this server.";
        }

        return "Retrieved " + invites.size() + " active invites:\n" +
                invites.stream()
                        .map(inv -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("- **").append(inv.getCode()).append("** (https://discord.gg/").append(inv.getCode()).append(")\n");
                            if (inv.getChannel() != null) {
                                sb.append("  • Channel: ").append(inv.getChannel().getName()).append(" (ID: ").append(inv.getChannel().getId()).append(")\n");
                            }
                            if (inv.getInviter() != null) {
                                sb.append("  • Created by: ").append(inv.getInviter().getName()).append(" (ID: ").append(inv.getInviter().getId()).append(")\n");
                            }
                            sb.append("  • Uses: ").append(inv.getUses());
                            sb.append(" | Max Uses: ").append(inv.getMaxUses() == 0 ? "Unlimited" : inv.getMaxUses());
                            sb.append(" | Max Age: ").append(inv.getMaxAge() == 0 ? "Never" : inv.getMaxAge() + "s");
                            sb.append(" | Temporary: ").append(inv.isTemporary());
                            return sb.toString();
                        })
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "delete_invite", description = "Delete (revoke) an invite so the link stops working")
    public String deleteInvite(
            @ToolParam(description = "Invite code or full URL (e.g. 'ABCde' or 'https://discord.gg/ABCde')") String inviteCode) {

        String code = extractInviteCode(inviteCode);
        Invite invite = Invite.resolve(jda, code).complete();
        invite.delete().complete();
        return "Successfully deleted invite: " + code;
    }

    @Tool(name = "get_invite_details", description = "Get details about a specific invite (works for any public invite)")
    public String getInviteDetails(
            @ToolParam(description = "Invite code or full URL (e.g. 'ABCde' or 'https://discord.gg/ABCde')") String inviteCode,
            @ToolParam(description = "Whether to include approximate member counts (default true)", required = false) String withCounts) {

        String code = extractInviteCode(inviteCode);
        boolean counts = withCounts == null || withCounts.isEmpty() || Boolean.parseBoolean(withCounts);

        Invite invite = Invite.resolve(jda, code, counts).complete();

        StringBuilder sb = new StringBuilder();
        sb.append("Invite: **").append(invite.getCode()).append("** (https://discord.gg/").append(invite.getCode()).append(")\n");
        if (invite.getGuild() != null) {
            sb.append("  • Server: ").append(invite.getGuild().getName()).append(" (ID: ").append(invite.getGuild().getId()).append(")\n");
        }
        if (invite.getChannel() != null) {
            sb.append("  • Channel: ").append(invite.getChannel().getName()).append(" (ID: ").append(invite.getChannel().getId()).append(")\n");
        }
        if (invite.getInviter() != null) {
            sb.append("  • Created by: ").append(invite.getInviter().getName()).append(" (ID: ").append(invite.getInviter().getId()).append(")\n");
        }
        if (counts) {
            sb.append("  • Members Online: ").append(invite.getGuild() != null ? invite.getGuild().getOnlineCount() : "N/A");
            sb.append(" | Total Members: ").append(invite.getGuild() != null ? invite.getGuild().getMemberCount() : "N/A");
        }
        return sb.toString();
    }
}
