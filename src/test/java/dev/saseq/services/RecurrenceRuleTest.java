package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import java.time.OffsetDateTime;
import static org.assertj.core.api.Assertions.*;

class RecurrenceRuleTest {
    static final String START = "2030-08-05T22:00:00-04:00";

    @Test void defaultsAnchorAndWeeklySelectorInUtc() {
        var rule = RecurrenceRule.parse("{\"frequency\":2}", START);
        assertThat(rule.getInt("interval")).isEqualTo(1);
        assertThat(OffsetDateTime.parse(rule.getString("start")).toInstant())
                .isEqualTo(OffsetDateTime.parse(START).toInstant());
        assertThat(rule.getArray("by_weekday").toList()).containsExactly(1);
        assertThat(RecurrenceRule.parse("null", START)).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "bad", "{\"frequency\":\"2\"}", "{\"frequency\":2.5}",
            "{\"frequency\":true}", "{\"frequency\":9}", "{\"frequency\":2,\"interval\":3}",
            "{\"frequency\":3,\"interval\":2}", "{\"frequency\":2,\"end\":null}",
            "{\"frequency\":2,\"typo\":1}", "{\"frequency\":2,\"by_weekday\":[0]}",
            "{\"frequency\":2,\"by_weekday\":[1,1]}", "{\"frequency\":2,\"by_weekday\":[]}",
            "{\"frequency\":2,\"start\":\"2030-08-06T03:00:00Z\"}",
            "{\"frequency\":1,\"by_weekday\":[1]}",
            "{\"frequency\":1,\"by_n_weekday\":[{\"n\":2,\"day\":1}]}",
            "{\"frequency\":0,\"by_month\":[8]}",
            "{\"frequency\":0,\"by_month\":[8],\"by_month_day\":[7]}",
            "{\"frequency\":2,\"by_weekday\":[1],\"by_n_weekday\":[{\"n\":1,\"day\":1}]}",
            "{\"frequency\":3,\"by_weekday\":[1.5]}"})
    void rejectsInvalidOrContradictoryRules(String json) {
        assertThatThrownBy(() -> RecurrenceRule.parse(json, START)).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "{\"frequency\":0,\"by_month\":[8],\"by_month_day\":[6]}",
            "{\"frequency\":1,\"by_n_weekday\":[{\"n\":1,\"day\":1}]}",
            "{\"frequency\":2,\"by_weekday\":[1]}",
            "{\"frequency\":3,\"by_weekday\":[0,1,2,3,4]}"
    })
    void preservesExplicitSelectorChoices(String json) {
        assertThat(RecurrenceRule.parse(json, START).toMap())
                .containsAllEntriesOf(DataObject.fromJson(json).toMap());
    }

    @Test void derivesMonthlyAndYearlySelectors() {
        assertThat(RecurrenceRule.parse("{\"frequency\":1}", START)
                .getArray("by_n_weekday").getObject(0).getInt("n")).isEqualTo(1);
        var yearly = RecurrenceRule.parse("{\"frequency\":0}", START);
        assertThat(yearly.getArray("by_month").toList()).containsExactly(8);
        assertThat(yearly.getArray("by_month_day").toList()).containsExactly(6);
    }

    @Test void movingRulePreservesIntervalAndRemovesServerOwnedFields() {
        var existing = RecurrenceRule.parse("{\"frequency\":2,\"interval\":2}", START)
                .put("count", 10).putNull("end").putNull("by_year_day");
        var moved = RecurrenceRule.move(existing, "2030-08-08T20:00:00Z");
        assertThat(moved.getInt("interval")).isEqualTo(2);
        assertThat(moved.getArray("by_weekday").toList()).containsExactly(3);
        assertThat(moved.keys()).doesNotContain("count", "end", "by_year_day");
        assertThat(existing.getArray("by_weekday").toList()).containsExactly(1);
    }

    @Test void dailyMoveDoesNotRotateSelectedDays() {
        var existing = RecurrenceRule.parse("{\"frequency\":3,\"by_weekday\":[0,1,2,3,4]}", START);
        var moved = RecurrenceRule.move(existing, "2030-08-08T20:00:00Z");
        assertThat(moved.getArray("by_weekday").toList()).containsExactly(0,1,2,3,4);
        assertThatThrownBy(() -> RecurrenceRule.move(existing, "2030-08-11T20:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @ParameterizedTest
    @ValueSource(strings = {
            "{\"frequency\":2,\"start\":\"invalid\",\"by_weekday\":[1]}",
            "{\"frequency\":2,\"start\":\"2030-08-06T02:00:00Z\",\"by_weekday\":\"invalid\"}",
            "{\"frequency\":1,\"start\":\"2030-08-06T02:00:00Z\",\"by_n_weekday\":[{\"n\":99,\"day\":1}]}"
    })
    void refusesToRepairMalformedSavedRules(String saved) {
        assertThatThrownBy(() -> RecurrenceRule.move(DataObject.fromJson(saved), "2030-08-08T20:00:00Z"))
                .isInstanceOf(IllegalArgumentException.class);
    }

}
