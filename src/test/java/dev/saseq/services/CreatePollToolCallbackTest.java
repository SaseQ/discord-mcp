package dev.saseq.services;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.messages.MessagePollData;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Exercises the real Spring AI {@link MethodToolCallbackProvider} conversion of {@link MessageService}
 * and asserts the generated JSON Schema of the {@code create_poll} tool.
 */
@ExtendWith(MockitoExtension.class)
class CreatePollToolCallbackTest {

    private static final String CHANNEL_ID = "123456789";
    private static final String MESSAGE_ID = "987654321";
    private static final String JUMP_URL = "https://discord.com/channels/1/" + CHANNEL_ID + "/" + MESSAGE_ID;

    private static final Set<String> EXPECTED_MESSAGE_TOOLS = Set.of(
            "send_message", "edit_message", "delete_message", "read_messages", "search_messages",
            "add_reaction", "remove_reaction", "get_attachment", "get_message", "get_message_replies",
            "create_poll");

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Mock
    private JDA jda;
    @Mock
    private TextChannel textChannel;
    @Mock
    private MessageCreateAction sendAction;
    @Mock
    private Message sentMessage;

    private MessageService service;

    @BeforeEach
    void setUp() {
        service = new MessageService(jda);
    }

    @Test
    void exposesCreatePollAsUniqueToolKeepingExistingTools() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder().toolObjects(service).build();
        Set<String> names = new HashSet<>();
        for (ToolCallback callback : provider.getToolCallbacks()) {
            names.add(callback.getToolDefinition().name());
        }

        assertEquals(EXPECTED_MESSAGE_TOOLS, names);
    }

    @Test
    void createPollSchemaHasExpectedTypesAndRequiredFields() throws Exception {
        JsonNode schema = objectMapper.readTree(createPollCallback().getToolDefinition().inputSchema());
        JsonNode properties = schema.get("properties");

        assertEquals("string", properties.get("channelId").get("type").asText());
        assertEquals("string", properties.get("question").get("type").asText());
        assertEquals("array", properties.get("answers").get("type").asText());
        assertEquals("string", properties.get("answers").get("items").get("type").asText());
        assertEquals("integer", properties.get("durationHours").get("type").asText());
        assertEquals("boolean", properties.get("allowMultiselect").get("type").asText());

        JsonNode required = schema.get("required");
        List<String> requiredFields = new ArrayList<>();
        required.forEach(node -> requiredFields.add(node.asText()));
        assertEquals(3, requiredFields.size());
        assertTrue(requiredFields.contains("channelId"));
        assertTrue(requiredFields.contains("question"));
        assertTrue(requiredFields.contains("answers"));
        assertFalse(requiredFields.contains("durationHours"));
        assertFalse(requiredFields.contains("allowMultiselect"));
    }

    @Test
    void callbackDeserializesRequiredParametersOnly() {
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(textChannel);
        when(textChannel.sendMessagePoll(any(MessagePollData.class))).thenReturn(sendAction);
        when(sendAction.complete()).thenReturn(sentMessage);
        when(sentMessage.getId()).thenReturn(MESSAGE_ID);
        when(sentMessage.getJumpUrl()).thenReturn(JUMP_URL);

        String result = createPollCallback().call(
                "{\"channelId\":\"" + CHANNEL_ID + "\",\"question\":\"Question?\",\"answers\":[\"A\",\"B\"]}");

        assertTrue(result.contains(MESSAGE_ID));
        assertTrue(result.contains(JUMP_URL));
        verify(textChannel).sendMessagePoll(any(MessagePollData.class));
    }

    @Test
    void callbackDeserializesListIntegerAndBooleanParameters() throws Exception {
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(textChannel);
        when(textChannel.sendMessagePoll(any(MessagePollData.class))).thenReturn(sendAction);
        when(sendAction.complete()).thenReturn(sentMessage);
        when(sentMessage.getId()).thenReturn(MESSAGE_ID);
        when(sentMessage.getJumpUrl()).thenReturn(JUMP_URL);

        String result = createPollCallback().call(
                "{\"channelId\":\"" + CHANNEL_ID + "\",\"question\":\"Question?\",\"answers\":[\"A\",\"B\"],"
                        + "\"durationHours\":168,\"allowMultiselect\":true}");

        assertTrue(result.contains(MESSAGE_ID));
        assertTrue(result.contains(JUMP_URL));
        verify(textChannel).sendMessagePoll(any(MessagePollData.class));
    }

    private ToolCallback createPollCallback() {
        ToolCallbackProvider provider = MethodToolCallbackProvider.builder().toolObjects(service).build();
        return Arrays.stream(provider.getToolCallbacks())
                .filter(callback -> "create_poll".equals(callback.getToolDefinition().name()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("create_poll tool not found"));
    }
}
