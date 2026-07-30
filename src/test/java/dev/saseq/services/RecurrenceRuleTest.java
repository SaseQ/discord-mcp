package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceRuleTest {

    private static final String START = "2026-08-05T20:00:00-05:00";

    @Test
    void acceptsAWeeklyRuleAndDefaultsItsAnchorToTheEventStart() {
        DataObject rule = RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [2]}", START);

        assertThat(rule.getInt("frequency")).isEqualTo(RecurrenceRule.WEEKLY);
        assertThat(rule.getInt("interval")).isEqualTo(1);
        assertThat(rule.getString("start")).isEqualTo(START);
    }

    @Test
    void rejectsFieldsDiscordWillNotAcceptFromAClient() {
        for (String field : new String[]{"count", "end", "by_year_day"}) {
            assertThatThrownBy(() -> RecurrenceRule.parse(
                    "{\"frequency\": 2, \"by_weekday\": [2], \"" + field + "\": 5}", START))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(field);
        }
    }

    @Test
    void rejectsAMultiDayWeeklyRule() {
        // Discord accepts exactly one weekday on a weekly rule. Rejecting it here turns an opaque
        // 400 into an explanation.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [0, 2, 4]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("exactly one day");
    }

    @Test
    void allowsAMultiDayDailyRule() {
        DataObject rule = RecurrenceRule.parse("{\"frequency\": 3, \"by_weekday\": [0,1,2,3,4]}", START);
        assertThat(rule.getArray("by_weekday").length()).isEqualTo(5);
    }

    @Test
    void rejectsIntervalAboveOneForAnythingButWeekly() {
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 3, \"interval\": 2}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("weekly");

        assertThat(RecurrenceRule.parse("{\"frequency\": 2, \"interval\": 2, \"by_weekday\": [2]}", START)
                .getInt("interval")).isEqualTo(2);
    }

    @Test
    void rejectsMutuallyExclusiveSelectors() {
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_weekday\": [2], \"by_n_weekday\": [{\"n\":1,\"day\":2}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("mutually exclusive");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_weekday\": [2], \"by_month\": [3], \"by_month_day\": [1]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cannot be combined");
    }

    @Test
    void enforcesFrequencyScopedSelectors() {
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"by_n_weekday\": [{\"n\":1,\"day\":2}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("monthly");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_month\": [3], \"by_month_day\": [1]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("yearly");

        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 0, \"by_month\": [3]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("together");
    }

    @Test
    void rejectsOutOfRangeValues() {
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 9}", START))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [7]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0=Monday");
        assertThatThrownBy(() -> RecurrenceRule.parse("not json", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("JSON object");
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"interval\": 1}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequency is required");
    }

    @Test
    void rejectsWeeklyIntervalsAboveTwo() {
        // Discord supports every week and every other week, and nothing beyond. Accepting 3 here
        // would create the event and only then have the recurrence PATCH rejected.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"interval\": 3, \"by_weekday\": [2]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 or 2");
    }

    @Test
    void withStartDropsServerOwnedFieldsAndMovesTheAnchor() {
        // Shaped like a real GET response: Discord returns count/end/by_year_day, and echoing
        // them back on a PATCH is rejected.
        DataObject fromDiscord = DataObject.fromJson(
                "{\"frequency\": 2, \"interval\": 1, \"by_weekday\": [2],"
                        + " \"start\": \"2026-01-07T21:15:00-05:00\","
                        + " \"count\": 12, \"end\": \"2026-12-31T00:00:00Z\", \"by_year_day\": null}");

        DataObject moved = RecurrenceRule.withStart(fromDiscord, START);

        assertThat(moved.getString("start")).isEqualTo(START);
        assertThat(moved.hasKey("count")).isFalse();
        assertThat(moved.hasKey("end")).isFalse();
        assertThat(moved.hasKey("by_year_day")).isFalse();
        // The selectors that define the series must survive the rebuild.
        assertThat(moved.getInt("frequency")).isEqualTo(RecurrenceRule.WEEKLY);
        assertThat(moved.getArray("by_weekday").getInt(0)).isEqualTo(2);
    }

    @Test
    void describesRulesInWordsSoRecurrenceIsVisible() {
        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [2]}", START)))
                .contains("weekly").contains("Wednesday").contains(START);

        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 2, \"interval\": 2, \"by_weekday\": [0]}", START)))
                .contains("every 2 weeks").contains("Monday");

        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 1, \"by_n_weekday\": [{\"n\":2,\"day\":4}]}", START)))
                .contains("occurrence 2").contains("Friday");
    }
}
