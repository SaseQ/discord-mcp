package dev.saseq.configs;

import dev.saseq.services.DiscordService;
import dev.saseq.services.MessageService;
import dev.saseq.services.UserService;
import dev.saseq.services.ChannelService;
import dev.saseq.services.CategoryService;
import dev.saseq.services.WebhookService;
import dev.saseq.services.ThreadService;
import dev.saseq.services.ModerationService;
import dev.saseq.services.RoleService;
import dev.saseq.services.VoiceChannelService;
import dev.saseq.services.ScheduledEventService;
import dev.saseq.services.InviteService;
import dev.saseq.services.ChannelPermissionService;
import dev.saseq.services.EmojiService;
import dev.saseq.services.ForumService;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class DiscordMcpConfig {
    @Bean
    public ToolCallbackProvider discordTools(DiscordService discordService,
                                             MessageService messageService,
                                             UserService userService,
                                             ChannelService channelService,
                                             CategoryService categoryService,
                                             WebhookService webhookService,
                                             ThreadService threadService,
                                             RoleService roleService,
                                             ModerationService moderationService,
                                             VoiceChannelService voiceChannelService,
                                             ScheduledEventService scheduledEventService,
                                             InviteService inviteService,
                                             ChannelPermissionService channelPermissionService,
                                             EmojiService emojiService,
                                             ForumService forumService) {
        return MethodToolCallbackProvider.builder().toolObjects(
                discordService,
                messageService,
                userService,
                channelService,
                categoryService,
                webhookService,
                threadService,
                roleService,
                moderationService,
                voiceChannelService,
                scheduledEventService,
                inviteService,
                channelPermissionService,
                emojiService,
                forumService
        ).build();
    }

    @Bean
    public JDA jda(@Value("${DISCORD_TOKEN:}") String token) throws InterruptedException {
        if (token == null || token.isEmpty()) {
            System.err.println("ERROR: The environment variable DISCORD_TOKEN is not set. Please set it to run the application properly.");
            System.exit(1);
        }
        try {
            return JDABuilder.createDefault(token)
                    .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.SCHEDULED_EVENTS)
                    .build()
                    .awaitReady();
        } catch (RuntimeException e) {
            // In the default stdio transport, logback writes only to a file so stdout stays a clean
            // MCP channel. That makes a startup failure here invisible to the MCP client, which just
            // sees the process exit and reports an opaque transport error (-32000). Echo an actionable
            // summary to stderr, which MCP clients capture and surface in their logs.
            reportJdaStartupFailure(e);
            System.exit(1);
            throw e; // unreachable (System.exit does not return), but required to satisfy the compiler
        }
    }

    private static void reportJdaStartupFailure(Throwable error) {
        StringBuilder chain = new StringBuilder();
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (chain.length() > 0) {
                chain.append(" | ");
            }
            chain.append(t.getMessage() != null ? t.getMessage() : t.getClass().getSimpleName());
        }
        String details = chain.toString();
        String lower = details.toLowerCase();

        System.err.println("ERROR: Could not connect to Discord; the MCP server cannot start.");
        if (lower.contains("token")) {
            System.err.println("  Likely cause: Discord rejected DISCORD_TOKEN (missing, malformed, or reset/revoked).");
            System.err.println("  Fix: create a fresh token in the Discord Developer Portal (Bot -> Reset Token) and set it");
            System.err.println("       WITHOUT surrounding quotes, e.g. DISCORD_TOKEN=abc... (an env-file keeps quotes literally).");
        } else if (lower.contains("intent") || lower.contains("4014")) {
            System.err.println("  Likely cause: a privileged gateway intent is not enabled for this bot.");
            System.err.println("  Fix: in the Discord Developer Portal (Bot -> Privileged Gateway Intents), enable");
            System.err.println("       'Server Members Intent' (GUILD_MEMBERS).");
        }
        System.err.println("  Details: " + details);
    }
}
