package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.managers.ScheduledEventManager;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;
import static org.mockito.Mockito.*;

class ScheduledEventServiceTest {
    static final String GUILD = "123", EVENT = "234";
    final JDA jda = mock(JDA.class);
    final Guild guild = mock(Guild.class);
    final ScheduledEvent event = mock(ScheduledEvent.class);
    final ScheduledEventManager manager = mock(ScheduledEventManager.class);
    final ScheduledEventApi api = mock(ScheduledEventApi.class);
    final ScheduledEventService service = new ScheduledEventService(jda, api);

    @BeforeEach void setup() {
        when(jda.getGuildById(GUILD)).thenReturn(guild);
        when(guild.getId()).thenReturn(GUILD);
        when(guild.getScheduledEventById(EVENT)).thenReturn(event);
        when(event.getManager()).thenReturn(manager);
        when(event.getName()).thenReturn("Event");
        when(event.getId()).thenReturn(EVENT);
    }

    DataObject current() {
        return DataObject.empty().put("scheduled_start_time", "2030-08-05T20:00:00-04:00")
                .put("scheduled_end_time", "2030-08-05T22:00:00-04:00").putNull("recurrence_rule")
                .put("status", 1).put("entity_type", 3);
    }

    void edit(String start, String end) {
        service.editScheduledEvent(GUILD, EVENT, null, null, null, start, null, end, null);
    }

    @Test void preservesDurationAndUsesFreshValues() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        edit("2030-08-05T23:00:00-04:00", null);
        verify(manager).setStartTime(OffsetDateTime.parse("2030-08-05T23:00:00-04:00"));
        verify(manager).setEndTime(OffsetDateTime.parse("2030-08-06T01:00:00-04:00"));
        verify(manager).complete();
        verify(event, never()).getStartTime();
    }

    @Test void explicitEndWinsAndCanBeEditedAlone() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        edit(null, "2030-08-05T23:00:00-04:00");
        verify(manager).setEndTime(OffsetDateTime.parse("2030-08-05T23:00:00-04:00"));
        verify(manager, never()).setStartTime(any());
    }

    @Test void explicitEndOverridesPreservedDuration() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        edit("2030-08-05T23:00:00-04:00", "2030-08-06T02:00:00-04:00");
        verify(manager).setEndTime(OffsetDateTime.parse("2030-08-06T02:00:00-04:00"));
    }

    @Test void preservesElapsedDurationAcrossOffsetChange() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        edit("2030-11-05T20:00:00-05:00", null);
        verify(manager).setEndTime(OffsetDateTime.parse("2030-11-05T22:00:00-05:00"));
    }

    @Test void eventWithoutEndDoesNotGainOne() {
        when(api.get(GUILD, EVENT)).thenReturn(current().putNull("scheduled_end_time"));
        edit("2030-08-06T20:00:00-04:00", null);
        verify(manager, never()).setEndTime(any());
        verify(manager).complete();
    }

    @Test void invalidOrUnknownTimesDoNotWrite() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        assertThatThrownBy(() -> edit(null, "2030-08-05T20:00:00-04:00")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> edit("tomorrow", null)).isInstanceOf(IllegalArgumentException.class);
        when(api.get(GUILD, EVENT)).thenReturn(current().put("scheduled_end_time", "2030-08-05T19:00:00-04:00"));
        assertThatThrownBy(() -> edit("2030-08-06T20:00:00-04:00", null)).isInstanceOf(IllegalArgumentException.class);
        when(api.get(GUILD, EVENT)).thenReturn(DataObject.empty());
        assertThatThrownBy(() -> edit("2030-08-06T20:00:00-04:00", null)).isInstanceOf(IllegalArgumentException.class);
        verify(manager, never()).complete();
    }

    @Test void renameUsesDefaultGuildAndNeedsNoExtraRead() {
        ReflectionTestUtils.setField(service, "defaultGuildId", GUILD);
        service.editScheduledEvent(null, EVENT, null, "renamed", null, null, null, null, null);
        verify(manager).setName("renamed");
        verify(manager).complete();
        verifyNoInteractions(api);
    }
    @Test void recurringEditSendsAllRequestedFieldsInOnePatch() {
        try (var transport = new DiscordTransport()) {
            var current = current().put("recurrence_rule", RecurrenceRule.parse("{\"frequency\":2}",
                    "2030-08-05T20:00:00-04:00").toMap());
            transport.replies.add(current.toString());
            transport.replies.add("{\"id\":\"234\",\"name\":\"renamed\"}");
            var target = new ScheduledEventService(jda, new ScheduledEventApi(transport.jda));
            target.editScheduledEvent(GUILD, EVENT, "3", "renamed", null, "2030-08-08T20:00:00Z",
                    "new location", null, null);
            assertThat(transport.requests).hasSize(2);
            assertThat(transport.requests.get(1).method()).isEqualTo("PATCH");
            var body = DataObject.fromJson(transport.bodies.get(1));
            assertThat(body.getInt("status")).isEqualTo(3);
            assertThat(body.getInt("entity_type")).isEqualTo(3);
            assertThat(body.hasKey("channel_id")).isTrue();
            assertThat(body.isNull("channel_id")).isTrue();
            assertThat(body.getString("name")).isEqualTo("renamed");
            assertThat(body.getObject("entity_metadata").getString("location")).isEqualTo("new location");
            assertThat(OffsetDateTime.parse(body.getString("scheduled_end_time")))
                    .isEqualTo(OffsetDateTime.parse("2030-08-08T22:00:00Z"));
            assertThat(body.getObject("recurrence_rule").getArray("by_weekday").toList()).containsExactly(3);
            verify(event, never()).getManager();
        }
    }

    @Test void recurringCreationUsesOnePostAndOrdinaryCreationKeepsJda() {
        var action = mock(net.dv8tion.jda.api.requests.restaction.ScheduledEventAction.class);
        when(guild.createScheduledEvent(eq("Event"), eq("location"), any(), any())).thenReturn(action);
        when(action.complete()).thenReturn(event);
        try (var transport = new DiscordTransport()) {
            transport.replies.add("{\"id\":\"234\",\"name\":\"Event\"}");
            var target = new ScheduledEventService(jda, new ScheduledEventApi(transport.jda));
            String created = target.createScheduledEvent(GUILD, "Event", null, "2030-08-05T20:00:00Z",
                    "2030-08-05T22:00:00Z", "3", null, "location", "{\"frequency\":2}");
            assertThat(created).contains("End: unavailable");
            assertThat(transport.requests).hasSize(1);
            assertThat(transport.requests.get(0).method()).isEqualTo("POST");
            var body = DataObject.fromJson(transport.bodies.get(0));
            assertThat(body.getInt("privacy_level")).isEqualTo(2);
            assertThat(body.getInt("entity_type")).isEqualTo(3);
            assertThat(body.getObject("recurrence_rule").getInt("frequency")).isEqualTo(2);
            verify(action, never()).complete();
            target.createScheduledEvent(GUILD, "Event", null, "2030-08-05T20:00:00Z",
                    "2030-08-05T22:00:00Z", "3", null, "location", null);
            verify(action).complete();
            assertThat(transport.requests).hasSize(1);
        }
    }

    @Test void rejectedCombinedEditDoesNotCompleteManagerOrRetry() {
        try (var transport = new DiscordTransport()) {
            transport.replies.add(current().toString());
            transport.replies.add("{\"code\":50013,\"message\":\"Missing Permissions\"}");
            var target = new ScheduledEventService(jda, new ScheduledEventApi(transport.jda));
            // Return a provider rejection specifically for PATCH, after the successful GET.
            transport.patchStatus = 403;
            assertThatThrownBy(() -> target.editScheduledEvent(GUILD, EVENT, "3", "name", null,
                    null, null, null, "{\"frequency\":2}"))
                    .isInstanceOf(net.dv8tion.jda.api.exceptions.ErrorResponseException.class);
            assertThat(transport.requests).hasSize(2);
            verify(event, never()).getManager();
        }
    }

    @Test void clearingAbsentRecurrenceStillPermitsOtherEdits() {
        when(api.get(GUILD, EVENT)).thenReturn(DataObject.empty().putNull("recurrence_rule"));
        when(api.edit(eq(GUILD), eq(EVENT), any())).thenReturn(DataObject.empty().put("id", EVENT).put("name", "renamed"));
        service.editScheduledEvent(GUILD, EVENT, null, "renamed", null, null, null, null, "null");
        var body = org.mockito.ArgumentCaptor.forClass(DataObject.class);
        verify(api).edit(eq(GUILD), eq(EVENT), body.capture());
        assertThat(body.getValue().isNull("recurrence_rule")).isTrue();
        assertThat(body.getValue().getString("name")).isEqualTo("renamed");
        verify(event, never()).getManager();
    }

    @Test void coverChecksTargetBeforeFetchAndRejectsTerminalEvents() {
        try (var fetch = mockStatic(RemoteFetchGuard.class)) {
            when(api.get(GUILD, EVENT)).thenThrow(new IllegalArgumentException("not found"));
            assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, "https://media.example/a"))
                    .hasMessage("not found");
            doReturn(current().put("id", EVENT).put("status", 3)).when(api).get(GUILD, EVENT);
            assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, "https://media.example/a"))
                    .isInstanceOf(IllegalArgumentException.class);
            fetch.verifyNoInteractions();
        }
    }

    @Test void coverUsesBoundedHttpsFetchAndOneImagePatch() {
        try (var transport = new DiscordTransport(); var fetch = mockStatic(RemoteFetchGuard.class)) {
            transport.replies.add(current().put("id", EVENT).put("status", 1).toString());
            transport.replies.add("{\"image\":\"abc123\"}");
            byte[] png = {(byte)137,80,78,71,13,10,26,10};
            fetch.when(() -> RemoteFetchGuard.fetch("https://media.example/a", 5 * 1024 * 1024, "image"))
                    .thenReturn(png);
            var target = new ScheduledEventService(jda, new ScheduledEventApi(transport.jda));
            assertThat(target.setScheduledEventImage(GUILD, EVENT, "https://media.example/a"))
                    .contains("https://cdn.discordapp.com/guild-events/234/abc123.png");
            assertThat(transport.requests).hasSize(2);
            assertThat(transport.requests.get(1).method()).isEqualTo("PATCH");
            var body = DataObject.fromJson(transport.bodies.get(1));
            assertThat(body.keys()).containsExactly("image");
            assertThat(body.getString("image")).startsWith("data:image/png;base64,");
            fetch.verify(() -> RemoteFetchGuard.fetch("https://media.example/a", 5 * 1024 * 1024, "image"));
        }
    }

    @Test void rejectsUnsupportedImagesAndUnavailableUrlIsNotAFailedWrite() {
        when(api.get(GUILD, EVENT)).thenReturn(current().put("id", EVENT).put("status", 1));
        when(api.edit(eq(GUILD), eq(EVENT), any())).thenReturn(DataObject.empty());
        try (var fetch = mockStatic(RemoteFetchGuard.class)) {
            assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, "http://media.example/a"))
                    .isInstanceOf(IllegalArgumentException.class);
            fetch.when(() -> RemoteFetchGuard.fetch("https://media.example/a", 5 * 1024 * 1024, "image"))
                    .thenReturn("GIF89a".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            assertThatThrownBy(() -> service.setScheduledEventImage(GUILD, EVENT, "https://media.example/a"))
                    .isInstanceOf(IllegalArgumentException.class);
            verify(api, never()).edit(any(), any(), any());
            fetch.when(() -> RemoteFetchGuard.fetch("https://media.example/a", 5 * 1024 * 1024, "image"))
                    .thenReturn(new byte[]{(byte)255,(byte)216,(byte)255});
            assertThat(service.setScheduledEventImage(GUILD, EVENT, "https://media.example/a"))
                    .contains("Updated", "234", "unavailable");
            verify(api).edit(eq(GUILD), eq(EVENT), any());
        }
    }

    @Test void listingEnrichesExistingOutputAndSurvivesExtraReadFailure() {
        when(guild.getScheduledEvents()).thenReturn(java.util.List.of(event));
        when(event.getInterestedUserCount()).thenReturn(7);
        when(api.list(GUILD, false)).thenReturn(net.dv8tion.jda.api.utils.data.DataArray.fromCollection(
                java.util.List.of(java.util.Map.of("id", EVENT, "image", "abc123", "recurrence_rule", java.util.Map.of("frequency", 2)))));
        assertThat(service.listScheduledEvents(GUILD, null))
                .contains("Event", "7 users", "Recurrence:", "frequency", "abc123.png");
        when(api.list(GUILD, false)).thenThrow(new IllegalStateException("offline"));
        assertThat(service.listScheduledEvents(GUILD, "false"))
                .contains("Event", "details unavailable").doesNotContain("Interested:");
        doReturn(net.dv8tion.jda.api.utils.data.DataArray.fromJson(
                "[{\"id\":\"234\",\"image\":null,\"recurrence_rule\":null}]")).when(api).list(GUILD, false);
        assertThat(service.listScheduledEvents(GUILD, null)).contains("Cover: none", "Recurrence: none");
        when(api.list(GUILD, false)).thenReturn(net.dv8tion.jda.api.utils.data.DataArray.fromJson("[{\"id\":\"234\"}]"));
        assertThat(service.listScheduledEvents(GUILD, null)).contains("Cover: unavailable", "Recurrence: unavailable");
    }

    @Test void discoversChangedOptionalArgumentsAndRequiredCoverArguments() {
        var callbacks = org.springframework.ai.tool.method.MethodToolCallbackProvider.builder()
                .toolObjects(service).build().getToolCallbacks();
        var tools = java.util.Arrays.stream(callbacks).collect(java.util.stream.Collectors.toMap(
                callback -> callback.getToolDefinition().name(), callback -> callback.getToolDefinition()));
        var cover = DataObject.fromJson(tools.get("set_guild_scheduled_event_image").inputSchema());
        assertThat(cover.getObject("properties").keys()).containsExactlyInAnyOrder("guildId", "eventId", "imageUrl");
        assertThat(cover.getArray("required").toList()).containsExactlyInAnyOrder("eventId", "imageUrl");
        var edit = DataObject.fromJson(tools.get("edit_guild_scheduled_event").inputSchema());
        assertThat(edit.getObject("properties").keys()).contains("scheduledEndTime", "recurrenceRule");
        assertThat(edit.getArray("required").toList()).doesNotContain("scheduledEndTime", "recurrenceRule");
        var create = DataObject.fromJson(tools.get("create_guild_scheduled_event").inputSchema());
        assertThat(create.getObject("properties").keys()).contains("recurrenceRule");
        assertThat(create.getArray("required").toList()).doesNotContain("recurrenceRule");
    }

    @Test void rawLocationEditPreservesExternalConversionInOnePatch() {
        when(api.get(GUILD, EVENT)).thenReturn(current().put("entity_type", 2).putNull("scheduled_end_time"));
        when(api.edit(eq(GUILD), eq(EVENT), any())).thenReturn(DataObject.empty().put("id", EVENT));
        service.editScheduledEvent(GUILD, EVENT, null, null, null, null, "venue",
                "2030-08-06T03:00:00Z", "{\"frequency\":2}");
        var body = org.mockito.ArgumentCaptor.forClass(DataObject.class);
        verify(api).edit(eq(GUILD), eq(EVENT), body.capture());
        assertThat(body.getValue().getInt("entity_type")).isEqualTo(3);
        assertThat(body.getValue().hasKey("channel_id")).isTrue();
        assertThat(body.getValue().isNull("channel_id")).isTrue();
        assertThat(body.getValue().getObject("entity_metadata").getString("location")).isEqualTo("venue");
        assertThat(body.getValue().getString("scheduled_end_time")).isEqualTo("2030-08-06T03:00Z");
        verify(event, never()).getManager();
    }

    @Test void rawConversionNeedsAnEndAndScheduledStatus() {
        when(api.get(GUILD, EVENT)).thenReturn(current().put("entity_type", 2).putNull("scheduled_end_time"));
        assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, null, null,
                "venue", null, "{\"frequency\":2}")).isInstanceOf(IllegalArgumentException.class);
        when(api.get(GUILD, EVENT)).thenReturn(current().put("entity_type", 2).put("status", 2));
        assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, null, null,
                "venue", null, "{\"frequency\":2}")).isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).edit(any(), any(), any());
    }

    @Test void activeExternalEventCanUpdateLocationWithExistingEnd() {
        when(api.get(GUILD, EVENT)).thenReturn(current().put("status", 2));
        when(api.edit(eq(GUILD), eq(EVENT), any())).thenReturn(DataObject.empty().put("id", EVENT));
        service.editScheduledEvent(GUILD, EVENT, null, null, null, null, "venue", null, "{\"frequency\":2}");
        var body = org.mockito.ArgumentCaptor.forClass(DataObject.class);
        verify(api).edit(eq(GUILD), eq(EVENT), body.capture());
        assertThat(body.getValue().getString("scheduled_end_time")).isEqualTo("2030-08-05T22:00-04:00");
    }

    @Test void rawEditsPreserveNameDescriptionAndLocationValidation() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        for (String bad : java.util.List.of(" ", "x".repeat(101))) {
            assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, bad, null, null,
                    null, null, "{\"frequency\":2}")).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, null, null,
                    bad, null, "{\"frequency\":2}")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, "x".repeat(1001),
                null, null, null, "{\"frequency\":2}")).isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).edit(any(), any(), any());
    }

    @Test void rawStartAndEndKeepJdaTimeRestrictions() {
        when(api.get(GUILD, EVENT)).thenReturn(current());
        for (String invalid : java.util.List.of(OffsetDateTime.now().minusDays(1).toString(),
                OffsetDateTime.now().plusYears(6).toString())) {
            assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, null,
                    invalid, null, null, "{\"frequency\":2}")).isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, null,
                null, null, OffsetDateTime.now().plusYears(6).toString(), "{\"frequency\":2}"))
                .isInstanceOf(IllegalArgumentException.class);
        when(api.get(GUILD, EVENT)).thenReturn(current().put("status", 2));
        assertThatThrownBy(() -> service.editScheduledEvent(GUILD, EVENT, null, null, null,
                "2030-08-08T20:00:00Z", null, null, "{\"frequency\":2}"))
                .isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).edit(any(), any(), any());
    }

    @Test void unreadableSavedRuleRefusesRescheduleWithoutPatch() {
        when(api.get(GUILD, EVENT)).thenReturn(current().put("recurrence_rule",
                DataObject.fromJson("{\"frequency\":2,\"start\":\"invalid\",\"by_weekday\":\"invalid\"}").toMap()));
        assertThatThrownBy(() -> edit("2030-08-08T20:00:00Z", null)).isInstanceOf(IllegalArgumentException.class);
        verify(api, never()).edit(any(), any(), any());
        verify(event, never()).getManager();
    }

}
