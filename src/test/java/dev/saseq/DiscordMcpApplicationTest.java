package dev.saseq;

import net.dv8tion.jda.api.JDA;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(properties = "DISCORD_TOKEN=test-token")
class DiscordMcpApplicationTest {

    @MockitoBean
    private JDA jda;

    @Autowired
    private ToolCallbackProvider toolCallbackProvider;

    @Test
    void contextLoads() {
        assertThat(toolCallbackProvider).isNotNull();
    }

    @Test
    void toolsAreRegistered() {
        var callbacks = toolCallbackProvider.getToolCallbacks();
        assertThat(callbacks).isNotEmpty();
    }
}
