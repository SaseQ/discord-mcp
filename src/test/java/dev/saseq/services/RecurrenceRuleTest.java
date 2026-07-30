package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RecurrenceRuleTest {

    // 20:00 on Wednesday 5 August in -05:00 is 01:00 on THURSDAY 6 August in UTC. Discord derives
    // selectors from the UTC date, so this anchor implies Thursday, month 8 day 6, and the first
    // Thursday of the month. Chosen deliberately: an evening event whose local and UTC dates differ
    // is exactly the case that used to derive the wrong day.
    private static final String START = "2026-08-05T20:00:00-05:00";

    @Test
    void acceptsAWeeklyRuleAndDefaultsItsAnchorToTheEventStart() {
        DataObject rule = RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [3]}", START);

        assertThat(rule.getInt("frequency")).isEqualTo(RecurrenceRule.WEEKLY);
        assertThat(rule.getInt("interval")).isEqualTo(1);
        assertThat(rule.getString("start")).isEqualTo(START);
    }

    @Test
    void rejectsFieldsDiscordWillNotAcceptFromAClient() {
        for (String field : new String[]{"count", "end", "by_year_day"}) {
            assertThatThrownBy(() -> RecurrenceRule.parse(
                    "{\"frequency\": 2, \"by_weekday\": [3], \"" + field + "\": 5}", START))
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

        assertThat(RecurrenceRule.parse("{\"frequency\": 2, \"interval\": 2, \"by_weekday\": [3]}", START)
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
                "{\"frequency\": 2, \"interval\": 3, \"by_weekday\": [3]}", START))
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
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 1, \"day\": 3}]}", START)
                .getArray("by_n_weekday").getObject(0).getInt("n")).isEqualTo(1);
    }

    @Test
    void fillsInTheSelectorEachFrequencyImpliesFromTheAnchor() {
        // START resolves to Thursday 6 August in UTC, the first Thursday of the month. For weekly,
        // monthly and yearly the selector is fully determined by the anchor, so omitting it is
        // filled in rather than rejected -- there is exactly one correct value.
        assertThat(RecurrenceRule.parse("{\"frequency\": 2}", START)
                .getArray("by_weekday").getInt(0)).isEqualTo(3);

        DataObject nth = RecurrenceRule.parse("{\"frequency\": 1}", START)
                .getArray("by_n_weekday").getObject(0);
        assertThat(nth.getInt("n")).isEqualTo(1);
        assertThat(nth.getInt("day")).isEqualTo(3);

        DataObject yearly = RecurrenceRule.parse("{\"frequency\": 0}", START);
        assertThat(yearly.getArray("by_month").getInt(0)).isEqualTo(8);
        assertThat(yearly.getArray("by_month_day").getInt(0)).isEqualTo(6);

        // Daily is exempt: its weekday set is a genuine choice, not implied by the date.
        assertThat(RecurrenceRule.parse("{\"frequency\": 3}", START).hasKey("by_weekday")).isFalse();
    }

    @Test
    void acceptsAnNWeekdaySelectorRegardlessOfMemberOrder() {
        // JSON member order carries no meaning, so {"day":2,"n":1} must be accepted exactly as
        // {"n":1,"day":2} is. Comparing serialised text would have rejected it.
        assertThat(RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"day\": 3, \"n\": 1}]}", START)
                .getArray("by_n_weekday").getObject(0).getInt("day")).isEqualTo(3);
    }

    @Test
    void rejectsFractionalNWeekdayFields() {
        // Same truncation trap as interval: getInt turns 1.9 into 1 while the fraction stays in
        // the payload and reaches Discord.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 1.9, \"day\": 3}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": 1, \"day\": 3.9}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void rejectsFractionalIntervals() {
        // getInt would truncate 1.9 to 1 and write it back, turning an unsupported value into a
        // silently different schedule.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"interval\": 1.9, \"by_weekday\": [3]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");
    }

    @Test
    void derivesSelectorsFromTheUtcDateAsDiscordDoes() {
        // Pinned against a real recurring event read back from Discord. Anchored at
        // 2026-06-26T02:00:00+00:00 -- Thursday 22:00 US Eastern, Friday in UTC -- Discord stores
        // by_weekday [4], Friday. Deriving from the local date would give Thursday and put the
        // series on the wrong day, which is why this is UTC and not the anchor's own offset.
        assertThat(RecurrenceRule.parse("{\"frequency\": 2}", "2026-06-25T22:00:00-04:00")
                .getArray("by_weekday").getInt(0)).isEqualTo(4);

        // The same instant spelled either way must produce the same schedule.
        assertThat(RecurrenceRule.parse("{\"frequency\": 2}", "2026-06-26T02:00:00+00:00")
                .getArray("by_weekday").getInt(0)).isEqualTo(4);
    }

    @Test
    void rejectsASelectorThatContradictsTheAnchor() {
        // START is Thursday in UTC, so [2] (Wednesday) contradicts its own anchor.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [2]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                // The message must name the UTC date and must NOT tell the caller to move the
                // start: for a late-evening event the start is already the date they meant, and
                // following that advice produces an event a day early.
                .hasMessageContaining("2026-08-06")
                .hasMessageContaining("in UTC")
                .hasMessageContaining("omit by_weekday");
    }

    @Test
    void handlesExplicitJsonNullsWithoutLeakingAParsingException() {
        // Models emit null for unset optional fields. These used to reach getInt and throw JDA's
        // ParsingException, which is not an IllegalArgumentException and escaped parse() entirely,
        // so the tool returned an opaque error naming no field.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": null}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("frequency is required");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 1, \"by_n_weekday\": [{\"n\": null, \"day\": 3}]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("n");

        // interval is optional, so an explicit null reads as unset rather than an error.
        assertThat(RecurrenceRule.parse("{\"frequency\": 2, \"interval\": null}", START)
                .getInt("interval")).isEqualTo(1);
    }

    @Test
    void parsesQuotedNumbersExactlyRatherThanThroughDouble() {
        // 1e-400 underflows to 0.0 as a double, which reads as whole and would normalise to 0 --
        // silently creating a YEARLY rule out of input that should have been rejected.
        // Quoted spellings only: an unquoted float is already a Double before this class sees it.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": \"1e-400\"}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");

        // Beyond double precision, so it rounds to exactly 2 and passes a floor check.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"interval\": \"2.0000000000000001\"}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 3, \"by_weekday\": [\"1e-400\", 1, 2, 3, 4]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("whole number");

        // Out of int range must be named rather than silently truncated.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": \"99999999999999999999\"}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("out of range");
    }

    @Test
    void blamesTheTimezoneOnlyWhenTheAnchorCrossesADate() {
        // A midday start whose local and UTC dates match: the caller mistyped the date, so telling
        // them the start is "almost certainly right" and to drop the selector would hand back the
        // wrong series reported as success.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"by_weekday\": [0]}", "2026-08-05T12:00:00-05:00"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Move start to the date you want")
                .hasMessageNotContaining("almost certainly right");

        // The evening case still gets the timezone explanation.
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [2]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("crosses into the next day in UTC");
    }

    @Test
    void namesANullSelectorElement() {
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 3, \"by_weekday\": [0,1,2,3,null]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("got null");
    }

    @Test
    void namesTheFieldWhenTheJsonShapeIsWrong() {
        // A scalar where an array or object belongs used to reach getArray/getObject and throw
        // JDA's ParsingException, which is not an IllegalArgumentException and escaped parse().
        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": 3}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("by_weekday must be an array");

        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 1, \"by_n_weekday\": [3]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be objects");
    }

    @Test
    void describesTheAnchorInTheSameFrameAsTheWeekday() {
        // "on Thursday (UTC), anchored at 2026-08-05T20:00-05:00" is a Wednesday anchor next to a
        // Thursday weekday, which reads as self-contradictory to the model consuming it.
        String described = RecurrenceRule.describe(RecurrenceRule.parse("{\"frequency\": 2}", START));
        assertThat(described).contains("Thursday").contains("2026-08-06");
    }

    @Test
    void normalisesWholeValuedDoublesAndNumericStrings() {
        // 3.0 is a whole number, so it must be accepted -- but unnormalised it survived into the
        // payload, where the agreement check compares DataArray.toString() and read [3.0] as
        // disagreeing with [3]. That told the caller their selector contradicted an anchor it
        // matched exactly.
        assertThat(RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [3.0]}", START)
                .getArray("by_weekday").toString()).isEqualTo("[3]");

        // Daily has no agreement check at all, so an unnormalised set shipped straight to Discord.
        assertThat(RecurrenceRule.parse("{\"frequency\": 3, \"by_weekday\": [0.0,1.0,2.0,3.0,4.0]}", START)
                .getArray("by_weekday").toString()).isEqualTo("[0,1,2,3,4]");

        // "2.0" parses as a whole number but then broke getInt with a field-less
        // NumberFormatException. Normalising on the way through fixes both.
        assertThat(RecurrenceRule.parse("{\"frequency\": \"2.0\"}", START)
                .getInt("frequency")).isEqualTo(RecurrenceRule.WEEKLY);

        assertThat(RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [8.0], \"by_month_day\": [6.0]}", START)
                .getArray("by_month_day").toString()).isEqualTo("[6]");
    }

    @Test
    void advisesOmittingBothYearlySelectorsTogether() {
        // "omit by_month_day" alone trips the supplied-together check, so a model following the
        // advice literally would hit a second, unrelated-looking error.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [8], \"by_month_day\": [5]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("omit by_month and by_month_day");
    }

    @Test
    void rejectsNonWholeNumbersOnEverySelectorAndOnStringSpellings() {
        // by_month/by_month_day were the two fields the shared helper never reached.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [8.9], \"by_month_day\": [6]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("by_month");

        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 0, \"by_month\": [8], \"by_month_day\": [6.5]}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("by_month_day");

        // A JSON string spelling used to slip past the Number check and surface as a bare
        // NumberFormatException naming no field.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"interval\": \"1.9\"}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("interval");
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
                "{\"frequency\": 0, \"by_month\": [8], \"by_month_day\": [6]}", START)
                .getArray("by_month").getInt(0)).isEqualTo(8);
    }

    @Test
    void rejectsAStartThatIsNotATimestamp() {
        // The event's own start time is validated separately, so a malformed anchor inside the
        // rule would otherwise create the event and fail only on the recurrence PATCH.
        assertThatThrownBy(() -> RecurrenceRule.parse(
                "{\"frequency\": 2, \"by_weekday\": [3], \"start\": \"not-a-timestamp\"}", START))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO8601");

        assertThatThrownBy(() -> RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [3]}", "nonsense"))
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
        assertThat(moved.getArray("by_weekday").getInt(0)).isEqualTo(3);
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
                "{\"frequency\": 2, \"by_weekday\": [3], \"nonsense\": true}", START))
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
        // START is Thursday 6 August in UTC (weekday 3). Moving to the 6th at -05:00 is Friday the
        // 7th in UTC, so by_weekday must move with it or the series contradicts its own anchor.
        DataObject thursdayUtc = DataObject.fromJson(
                "{\"frequency\": 2, \"interval\": 1, \"by_weekday\": [3], \"start\": \"" + START + "\"}");

        DataObject moved = RecurrenceRule.withStart(thursdayUtc, "2026-08-06T20:00:00-05:00");
        assertThat(moved.getArray("by_weekday").getInt(0)).isEqualTo(4);   // Friday in UTC

        // Same weekday, different time: the selector must NOT drift.
        DataObject sameDay = RecurrenceRule.withStart(thursdayUtc, "2026-08-05T21:00:00-05:00");
        assertThat(sameDay.getArray("by_weekday").getInt(0)).isEqualTo(3);
    }

    @Test
    void movingAcrossDatesUpdatesMonthlyAndYearlySelectors() {
        DataObject monthly = DataObject.fromJson(
                "{\"frequency\": 1, \"interval\": 1, \"by_n_weekday\": [{\"n\": 1, \"day\": 3}],"
                        + " \"start\": \"" + START + "\"}");
        // 20:00 on Thursday 20 August at -05:00 is 01:00 on FRIDAY 21 August in UTC, and the 21st
        // is the third Friday. Derived from the UTC date, as Discord does.
        DataObject movedMonthly = RecurrenceRule.withStart(monthly, "2026-08-20T20:00:00-05:00");
        DataObject nth = movedMonthly.getArray("by_n_weekday").getObject(0);
        assertThat(nth.getInt("n")).isEqualTo(3);
        assertThat(nth.getInt("day")).isEqualTo(4);

        DataObject yearly = DataObject.fromJson(
                "{\"frequency\": 0, \"interval\": 1, \"by_month\": [8], \"by_month_day\": [6],"
                        + " \"start\": \"" + START + "\"}");
        // 20:00 on 25 December at -06:00 is 02:00 on the 26th in UTC.
        DataObject movedYearly = RecurrenceRule.withStart(yearly, "2026-12-25T20:00:00-06:00");
        assertThat(movedYearly.getArray("by_month").getInt(0)).isEqualTo(12);
        assertThat(movedYearly.getArray("by_month_day").getInt(0)).isEqualTo(26);
    }

    @Test
    void describesRulesInWordsSoRecurrenceIsVisible() {
        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 2, \"by_weekday\": [3]}", START)))
                // The anchor is shown in UTC so it agrees with the weekday beside it.
                .contains("weekly").contains("Thursday").contains("2026-08-06T01:00Z");

        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 2, \"interval\": 2, \"by_weekday\": [3]}", START)))
                .contains("every 2 weeks").contains("Thursday");

        assertThat(RecurrenceRule.describe(
                RecurrenceRule.parse("{\"frequency\": 1, \"by_n_weekday\": [{\"n\":1,\"day\":2}]}", "2026-08-05T12:00:00-05:00")))
                .contains("occurrence 1").contains("Wednesday");
    }
}
