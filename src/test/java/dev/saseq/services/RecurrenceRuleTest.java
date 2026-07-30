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
    void validatesTheContentsOfAnNWeekdayEntryNotJustItsLength() {
        // n is which occurrence in the month (1-5) and day is 0=Monday..6=Sunday. Checking only
        // the array length let a bad entry create the event and fail on the recurrence PATCH.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 0, \"day\": 2}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n must be 1 to 5");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 6, \"day\": 2}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n must be 1 to 5");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 2, \"day\": 7}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("0=Monday");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 2}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("both n and day");

        assertThat(RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 1, \"day\": 2}]}", START)
                .getArray("by_n_weekday").getObject(0).getInt("n")).isEqualTo(1);
    }

    @Test
    void fillsInTheSelectorEachFrequencyImpliesFromTheAnchor() {
        // START is Wednesday 2026-08-05, the first Wednesday of August. For weekly, monthly and
        // yearly the selector is fully determined by that, so omitting it is filled in rather than
        // rejected -- there is exactly one correct value.
        assertThat(RecurrenceRule.parse("{\"frequency\": 2}", START)
                .getArray("by_weekday").getInt(0)).isEqualTo(2);

        DataObject nth = RecurrenceRule.parse("{\"frequency\": 1}", START)
                .getArray("by_n_weekday").getObject(0);
        assertThat(nth.getInt("n")).isEqualTo(1);
        assertThat(nth.getInt("day")).isEqualTo(2);

        DataObject yearly = RecurrenceRule.parse("{\"frequency\": 0}", START);
        assertThat(yearly.getArray("by_month").getInt(0)).isEqualTo(8);
        assertThat(yearly.getArray("by_month_day").getInt(0)).isEqualTo(5);

        // Daily is exempt: its weekday set is a genuine choice, not implied by the date.
        assertThat(RecurrenceRule.parse("{\"frequency\": 3}", START).hasKey("by_weekday")).isFalse();
    }

    @Test
    void acceptsAnNWeekdaySelectorRegardlessOfMemberOrder() {
        // JSON member order carries no meaning, so {"day":2,"n":1} must be accepted exactly as
        // {"n":1,"day":2} is. Comparing serialised text would have rejected it.
        assertThat(RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"day\": 2, \"n\": 1}]}", START)
                .getArray("by_n_weekday").getObject(0).getInt("day")).isEqualTo(2);
    }

    @Test
    void rejectsFractionalNWeekdayFields() {
        // Same truncation trap as interval: getInt turns 1.9 into 1 while the fraction stays in
        // the payload and reaches Discord.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 1.9, \"day\": 2}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 1, \"day\": 2.9}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void rejectsFractionalIntervals() {
        // getInt would truncate 1.9 to 1 and write it back, turning an unsupported value into a
        // silently different schedule.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"interval\": 1.9, \"by_weekday\": [2]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void rejectsASelectorThatContradictsTheAnchor() {
        // A Wednesday anchor with a Thursday by_weekday is not a Thursday series, it is an
        // incoherent rule that Discord rejects or silently reinterprets.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [3]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("falls on");
    }

    @Test
    void validatesYearlySelectorValues() {
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [0], \"by_month_day\": [8]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 (January) through 12");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [4], \"by_month_day\": [32]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("1 through 31");

        assertThat(RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [8], \"by_month_day\": [5]}", START)
                .getArray("by_month").getInt(0)).isEqualTo(8);
    }

    @Test
    void rejectsAStartThatIsNotATimestamp() {
        // The event's own start time is validated separately, so a malformed anchor inside the
        // rule would otherwise create the event and fail only on the recurrence PATCH.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"by_weekday\": [2], \"start\": \"not-a-timestamp\"}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO8601");

        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [2]}", "nonsense"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO8601");
    }

    @Test
    void acceptsOnlyKnownWeekdaySetsForDailyRules() {
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 3, \"by_weekday\": [0, 2, 4]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("known weekday set");

        // Weekdays, weekend, and all seven are the accepted sets.
        assertThat(RecurrenceRule.parse("{\"frequency\": 3, \"by_weekday\": [5,6]}", START)).isNotNull();
        assertThat(RecurrenceRule.parse("{\"frequency\": 3, \"by_weekday\": [0,1,2,3,4,5,6]}", START)).isNotNull();
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
    void rejectsFieldsOutsideTheWritableSchema() {
        // A near-miss on a daily rule is the dangerous case: daily needs no selector, so nothing
        // else would have caught it, and Discord would ignore the key and build a different
        // schedule than the one asked for.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 3, \"by_weekdays\": [0,1,2,3,4]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("by_weekdays");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"by_weekday\": [2], \"nonsense\": true}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unrecognised field");
    }

    @Test
    void rejectsExplicitlyEmptySelectorArrays() {
        // hasArray() treats an empty array as absent, but the empty key still ships in the payload
        // and Discord rejects it, so it has to fail here rather than after the event is created.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 3, \"by_weekday\": []}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("is empty");
    }

    @Test
    void movingAcrossDatesMovesTheSelectorToo() {
        // START is 2026-08-05, a Wednesday (weekday 2). Moving to the 6th, a Thursday, must carry
        // by_weekday with it or the series contradicts its own anchor.
        DataObject wednesday = DataObject.fromJson(
                "{\"frequency\": 2, \"interval\": 1, \"by_weekday\": [2], \"start\": \"" + START + "\"}");

        DataObject moved = RecurrenceRule.withStart(wednesday, "2026-08-06T20:00:00-05:00");
        assertThat(moved.getArray("by_weekday").getInt(0)).isEqualTo(3);   // Thursday

        // Same weekday, different time: the selector must NOT drift.
        DataObject sameDay = RecurrenceRule.withStart(wednesday, "2026-08-05T21:00:00-05:00");
        assertThat(sameDay.getArray("by_weekday").getInt(0)).isEqualTo(2);
    }

    @Test
    void movingAcrossDatesUpdatesMonthlyAndYearlySelectors() {
        DataObject monthly = DataObject.fromJson(
                "{\"frequency\": 1, \"interval\": 1, \"by_n_weekday\": [{\"n\": 1, \"day\": 2}],"
                        + " \"start\": \"" + START + "\"}");
        // 2026-08-20 is a Thursday and the third Thursday of the month.
        DataObject movedMonthly = RecurrenceRule.withStart(monthly, "2026-08-20T20:00:00-05:00");
        DataObject nth = movedMonthly.getArray("by_n_weekday").getObject(0);
        assertThat(nth.getInt("n")).isEqualTo(3);
        assertThat(nth.getInt("day")).isEqualTo(3);

        DataObject yearly = DataObject.fromJson(
                "{\"frequency\": 0, \"interval\": 1, \"by_month\": [8], \"by_month_day\": [5],"
                        + " \"start\": \"" + START + "\"}");
        DataObject movedYearly = RecurrenceRule.withStart(yearly, "2026-12-25T20:00:00-06:00");
        assertThat(movedYearly.getArray("by_month").getInt(0)).isEqualTo(12);
        assertThat(movedYearly.getArray("by_month_day").getInt(0)).isEqualTo(25);
    }

    @Test
    void describesRulesInWordsSoRecurrenceIsVisible() {
        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [2]}", START)))
                .contains("weekly").contains("Wednesday").contains(START);

        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 2, \"interval\": 2, \"by_weekday\": [2]}", START)))
                .contains("every 2 weeks").contains("Wednesday");

        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 1, \"by_n_weekday\": [{\"n\":1,\"day\":2}]}", START)))
                .contains("occurrence 1").contains("Wednesday");
    }
}
