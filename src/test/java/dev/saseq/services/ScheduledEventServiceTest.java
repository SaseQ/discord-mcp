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
                .put("scheduled_end_time", "2030-08-05T22:00:00-04:00").putNull("recurrence_rule");
    }

    void edit(String start, String end) {
        service.editScheduledEvent(GUILD, EVENT, null, null, null, start, null, end);
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
        service.editScheduledEvent(null, EVENT, null, "renamed", null, null, null, null);
        verify(manager).setName("renamed");
        verify(manager).complete();
        verifyNoInteractions(api);
    }
}
