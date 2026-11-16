package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class RecurrenceRule {
    private static final int YEARLY = 0;
    private static final int MONTHLY = 1;
    private static final int WEEKLY = 2;
    private static final int DAILY = 3;
    private static final Set<String> WRITABLE_FIELDS = Set.of("frequency", "interval", "start", "by_weekday",
            "by_n_weekday", "by_month", "by_month_day");
    private static final Set<String> SERVER_MANAGED_FIELDS = Set.of("count", "end", "by_year_day");

    private RecurrenceRule() { }

    static DataObject parse(String json, String effectiveStart) {
        if (json.trim().equals("null")) return null;
        try {
            DataObject rule = DataObject.fromJson(json);
            validateWritableFields(rule);
            int frequency = normalizeFrequencyAndInterval(rule);
            OffsetDateTime utcStart = normalizeAnchor(rule, effectiveStart);
            validateSingleSelectorFamily(rule);
            switch (frequency) {
                case DAILY, WEEKLY -> normalizeWeekdaySelector(rule, frequency, utcStart);
                case MONTHLY -> normalizeMonthlySelector(rule, utcStart);
                case YEARLY -> normalizeYearlySelector(rule, utcStart);
            }
            return rule;
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("Invalid recurrenceRule JSON or fields", e);
        }
    }

    static DataObject move(DataObject saved, String start) {
        DataObject rule = copyWritableFields(saved);
        rule = parse(rule.toString(), rule.getString("start", null));
        rule.put("start", start);
        clearAnchorDerivedSelectors(rule);
        return parse(rule.toString(), start);
    }

    private static void validateWritableFields(DataObject rule) {
        for (String key : rule.keys()) {
            if (!WRITABLE_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Unsupported recurrence field: " + key);
            }
        }
    }

    private static int normalizeFrequencyAndInterval(DataObject rule) {
        int frequency = requireIntegerInRange(rule.toMap().get("frequency"), "frequency", YEARLY, DAILY);
        rule.put("frequency", frequency);
        int interval = rule.hasKey("interval")
                ? requireIntegerInRange(rule.toMap().get("interval"), "interval", 1, 2) : 1;
        if (interval != 1 && frequency != WEEKLY) {
            throw new IllegalArgumentException("Only weekly rules support interval 2");
        }
        rule.put("interval", interval);
        return frequency;
    }

    private static OffsetDateTime normalizeAnchor(DataObject rule, String effectiveStart) {
        OffsetDateTime start = OffsetDateTime.parse(effectiveStart);
        OffsetDateTime anchor = rule.hasKey("start") ? OffsetDateTime.parse(rule.getString("start")) : start;
        if (!anchor.toInstant().equals(start.toInstant())) {
            throw new IllegalArgumentException("Recurrence anchor must match event start");
        }
        rule.put("start", start.toString());
        return start.withOffsetSameInstant(ZoneOffset.UTC);
    }

    private static void validateSingleSelectorFamily(DataObject rule) {
        int families = (hasSelector(rule, "by_weekday") ? 1 : 0)
                + (hasSelector(rule, "by_n_weekday") ? 1 : 0)
                + (hasSelector(rule, "by_month") || hasSelector(rule, "by_month_day") ? 1 : 0);
        if (families > 1) throw new IllegalArgumentException("Recurrence selectors are mutually exclusive");
    }

    private static void normalizeWeekdaySelector(DataObject rule, int frequency, OffsetDateTime utcStart) {
        if (hasSelector(rule, "by_n_weekday") || hasSelector(rule, "by_month") || hasSelector(rule, "by_month_day")) {
            throw new IllegalArgumentException("Daily/weekly rules require weekday selectors");
        }
        int startWeekday = utcStart.getDayOfWeek().getValue() - 1;
        if (!hasSelector(rule, "by_weekday") && frequency == WEEKLY) {
            rule.put("by_weekday", List.of(startWeekday));
        }
        if (hasSelector(rule, "by_weekday")) {
            List<Integer> weekdays = readDistinctIntegers(rule.getArray("by_weekday"), "by_weekday", 0, 6);
            if ((frequency == WEEKLY && weekdays.size() != 1) || !weekdays.contains(startWeekday)) {
                throw new IllegalArgumentException("Recurrence weekdays must match the start");
            }
        }
    }

    private static void normalizeMonthlySelector(DataObject rule, OffsetDateTime utcStart) {
        if (hasSelector(rule, "by_weekday") || hasSelector(rule, "by_month") || hasSelector(rule, "by_month_day")) {
            throw new IllegalArgumentException("Monthly rules require an nth-weekday selector");
        }
        int startWeekday = utcStart.getDayOfWeek().getValue() - 1;
        int weekdayOccurrence = (utcStart.getDayOfMonth() - 1) / 7 + 1;
        if (!hasSelector(rule, "by_n_weekday")) {
            rule.put("by_n_weekday", List.of(Map.of("n", weekdayOccurrence, "day", startWeekday)));
        }
        DataArray selectors = rule.getArray("by_n_weekday");
        if (selectors.length() != 1) throw new IllegalArgumentException("Use one monthly selector");
        DataObject selector = selectors.getObject(0);
        if (!selector.keys().equals(Set.of("n", "day"))
                || requireIntegerInRange(selector.toMap().get("n"), "n", 1, 5) != weekdayOccurrence
                || requireIntegerInRange(selector.toMap().get("day"), "day", 0, 6) != startWeekday) {
            throw new IllegalArgumentException("Monthly selector must match the start");
        }
    }

    private static void normalizeYearlySelector(DataObject rule, OffsetDateTime utcStart) {
        if (hasSelector(rule, "by_weekday") || hasSelector(rule, "by_n_weekday")) {
            throw new IllegalArgumentException("Yearly rules require month and day selectors");
        }
        if (!hasSelector(rule, "by_month") && !hasSelector(rule, "by_month_day")) {
            rule.put("by_month", List.of(utcStart.getMonthValue()))
                    .put("by_month_day", List.of(utcStart.getDayOfMonth()));
        }
        if (!readDistinctIntegers(rule.getArray("by_month"), "by_month", 1, 12).equals(List.of(utcStart.getMonthValue()))
                || !readDistinctIntegers(rule.getArray("by_month_day"), "by_month_day", 1, 31)
                        .equals(List.of(utcStart.getDayOfMonth()))) {
            throw new IllegalArgumentException("Yearly selector must match the start");
        }
    }

    private static DataObject copyWritableFields(DataObject saved) {
        DataObject rule = DataObject.empty();
        Map<String, Object> fields = saved.toMap();
        for (String key : saved.keys()) {
            if (WRITABLE_FIELDS.contains(key)) rule.put(key, fields.get(key));
            else if (!SERVER_MANAGED_FIELDS.contains(key)) {
                throw new IllegalArgumentException("Unreadable recurrence field: " + key);
            }
        }
        return rule;
    }

    private static void clearAnchorDerivedSelectors(DataObject rule) {
        switch (rule.getInt("frequency")) {
            case WEEKLY -> rule.remove("by_weekday");
            case MONTHLY -> rule.remove("by_n_weekday");
            case YEARLY -> {
                rule.remove("by_month");
                rule.remove("by_month_day");
            }
        }
    }

    private static boolean hasSelector(DataObject rule, String field) {
        return rule.hasKey(field) && !rule.isNull(field);
    }

    private static List<Integer> readDistinctIntegers(DataArray array, String field, int min, int max) {
        List<Integer> values = array.toList().stream()
                .map(value -> requireIntegerInRange(value, field, min, max)).toList();
        if (values.isEmpty() || new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must contain distinct values");
        }
        return values;
    }

    private static int requireIntegerInRange(Object value, String field, int min, int max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(field + " must be an integer");
        try {
            int number = new BigDecimal(value.toString()).intValueExact();
            if (number < min || number > max) throw new IllegalArgumentException(field + " out of range");
            return number;
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(field + " must be an integer", e);
        }
    }
}
