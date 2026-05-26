package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.JDABuilder;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.GatewayIntent;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class DiscordLiveIntegrationTest {

    private static JDA jda;
    private static String guildId;
    private static UserService userService;
    private static MessageService messageService;

    @BeforeAll
    static void setUpDiscord() throws InterruptedException {
        String token = System.getenv("DISCORD_TOKEN");
        guildId = System.getenv("DISCORD_GUILD_ID");
        Assumptions.assumeTrue(isProvided(token) && isProvided(guildId),
                "Set DISCORD_TOKEN and DISCORD_GUILD_ID to run live Discord integration tests");

        jda = JDABuilder.createDefault(token)
                .enableIntents(GatewayIntent.GUILD_MEMBERS, GatewayIntent.GUILD_VOICE_STATES, GatewayIntent.SCHEDULED_EVENTS)
                .build()
                .awaitReady();
        Assumptions.assumeTrue(jda.getGuildById(guildId) != null,
                "DISCORD_GUILD_ID must identify a guild visible to the bot");

        userService = new UserService(jda);
        messageService = new MessageService(jda);
    }

    @AfterAll
    static void tearDownDiscord() {
        if (jda != null) {
            jda.shutdownNow();
        }
    }

    @Test
    void getMemberByIdFindsTheBotItself() {
        String botUserId = jda.getSelfUser().getId();

        String result = userService.getMemberById(botUserId, guildId);

        assertThat(result)
                .contains("Member found")
                .contains("User ID: " + botUserId)
                .contains("Username: " + jda.getSelfUser().getName())
                .contains("Joined:")
                .contains("Roles:");
    }

    @Test
    void searchMembersFindsExactSnowflake() {
        String botUserId = jda.getSelfUser().getId();

        String result = userService.searchMembers(botUserId, "1", guildId);

        assertThat(result)
                .contains("Found 1 member candidate")
                .contains("User ID: " + botUserId)
                .contains("Username: " + jda.getSelfUser().getName())
                .doesNotContain("Joined:")
                .doesNotContain("Roles:");
    }

    @Test
    void searchMembersFindsUsernameQuery() {
        String query = System.getenv("DISCORD_TEST_MEMBER_QUERY");
        if (!isProvided(query)) {
            query = jda.getSelfUser().getName();
        }

        String result = userService.searchMembers(query, "5", guildId);

        assertThat(result)
                .contains("member candidate")
                .contains("User ID:")
                .contains("Username:")
                .doesNotContain("Joined:")
                .doesNotContain("Roles:");
    }

    @Test
    void readMessagesIncludesAuthorIdentityWhenChannelConfigured() {
        String channelId = System.getenv("DISCORD_TEST_CHANNEL_ID");
        String result = null;

        if (isProvided(channelId)) {
            result = messageService.readMessages(channelId, "1", null, null, null);
        } else {
            for (TextChannel channel : jda.getTextChannels()) {
                try {
                    String candidateResult = messageService.readMessages(channel.getId(), "1", null, null, null);
                    if (candidateResult.contains("Author ID:")) {
                        result = candidateResult;
                        break;
                    }
                } catch (RuntimeException ignored) {
                    // Try the next visible text channel. Some channels may deny history access.
                }
            }
        }

        Assumptions.assumeTrue(isProvided(result),
                "Set DISCORD_TEST_CHANNEL_ID to a readable text channel with at least one message");

        assertThat(result)
                .contains("Retrieved")
                .contains("Author ID:")
                .contains("Global:")
                .contains("Nickname:")
                .contains("Effective:");
    }

    private static boolean isProvided(String value) {
        return value != null && !value.isBlank();
    }
}
