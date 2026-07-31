package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class ScheduledEventServiceTest {

    private ScheduledEventService service;

    @BeforeEach
    void setUp() {
        service = new ScheduledEventService(mock(JDA.class));
    }

    @Test
    void movingTheStartCarriesTheEndAlongAndKeepsTheDuration() {
        // The case that produced this: three weekly classes shunted four weeks out. Discord
        // rejects a start that lands after the stored end, and the rejection does not say so.
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null);

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-09-02T21:30:00-05:00"));
    }

    @Test
    void theDurationComesFromTheLiveResponseNotACachedEntity() {
        // Discord normalises the timestamps it returns to UTC, so the live values routinely
        // differ textually from what anyone sent. A 90-minute event must shift by 90 minutes
        // regardless of how its times are spelled — and reading them from here rather than from
        // JDA's cache is what makes that true after an out-of-band edit.
        DataObject event = event("2026-08-06T01:00:00+00:00", "2026-08-06T02:30:00+00:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null);

        assertThat(Duration.between(OffsetDateTime.parse("2026-09-02T20:00:00-05:00"), end))
                .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void anExplicitEndTimeWins() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T23:00:00-05:00");

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-09-02T23:00:00-05:00"));
    }

    @Test
    void anExplicitEndTimeAppliesWithoutMovingTheStart() {
        // Lengthening an event in place: no start move, so no delta to apply, but the caller's
        // end must still be honoured.
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, null, "2026-08-05T22:00:00-05:00");

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-08-05T22:00:00-05:00"));
    }

    @Test
    void anEndAtOrBeforeTheNewStartIsRejectedHereRatherThanByDiscord() {
        // Sending this fails the whole manager update with the opaque server-side error this
        // parameter exists to stop people running into.
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T19:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not after the start time")
                .hasMessageContaining("omit it and it will follow the start automatically");

        // Equal is not "after" either: a zero-length event is rejected the same way.
        assertThatThrownBy(() -> service.resolveEndTime(
                event, "2026-09-02T20:00:00-05:00", "2026-09-02T20:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not after the start time");
    }

    @Test
    void anEndOnlyEditIsCheckedAgainstTheExistingStart() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(event, null, "2026-08-05T19:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is not after the start time")
                .hasMessageContaining("Pass scheduledStartTime too");
    }

    @Test
    void anEditThatTouchesNeitherTimeLeavesTheEndAlone() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThat(service.resolveEndTime(event, null, null)).isNull();
        assertThat(service.resolveEndTime(event, "", "")).isNull();
    }

    @Test
    void anEventWithNoEndTimeDoesNotGainOne() {
        // Stage and voice events have no end time. Inventing one would impose a constraint the
        // event did not have, and Discord would start enforcing it.
        DataObject event = event("2026-08-05T20:00:00-05:00", null);

        assertThat(service.resolveEndTime(event, "2026-09-02T20:00:00-05:00", null)).isNull();
    }

    @Test
    void durationIsPreservedAcrossADaylightSavingBoundary() {
        // 2026-11-01 is when US clocks go back. A 90-minute class moved across it should still
        // run 90 minutes — shifting the wall-clock end instead would make it 30 minutes longer.
        DataObject event = event("2026-10-28T20:00:00-05:00", "2026-10-28T21:30:00-05:00");

        OffsetDateTime end = service.resolveEndTime(event, "2026-11-04T20:00:00-06:00", null);

        assertThat(end).isEqualTo(OffsetDateTime.parse("2026-11-04T21:30:00-06:00"));
        assertThat(Duration.between(OffsetDateTime.parse("2026-11-04T20:00:00-06:00"), end))
                .isEqualTo(Duration.ofMinutes(90));
    }

    @Test
    void aMalformedEndTimeIsRejectedRatherThanIgnored() {
        DataObject event = event("2026-08-05T20:00:00-05:00", "2026-08-05T21:30:00-05:00");

        assertThatThrownBy(() -> service.resolveEndTime(event, null, "next tuesday"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Invalid ISO8601 timestamp");
    }

    @Test
    void anUnreadableEventStillAcceptsAnEndOnlyEdit() {
        // The empty object the caller substitutes when a best-effort read failed. Validation is
        // skipped rather than the edit refused: the read is only best-effort in the cases that
        // do not move the start, and Discord remains the backstop.
        assertThat(service.resolveEndTime(DataObject.empty(), null, "2026-08-05T22:00:00-05:00"))
                .isEqualTo(OffsetDateTime.parse("2026-08-05T22:00:00-05:00"));
    }

    private DataObject event(String start, String end) {
        DataObject raw = DataObject.empty().put("scheduled_start_time", start);
        if (end != null) {
            raw.put("scheduled_end_time", end);
        }
        return raw;
    }
}
