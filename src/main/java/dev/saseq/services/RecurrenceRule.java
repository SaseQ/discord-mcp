package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.HashSet;

final class RecurrenceRule {
    private static final Set<String> WRITABLE = Set.of("frequency", "interval", "start", "by_weekday",
            "by_n_weekday", "by_month", "by_month_day");
    private static final Set<String> READ_ONLY = Set.of("count", "end", "by_year_day");
    private RecurrenceRule() { }

    static DataObject parse(String json, String effectiveStart) {
        if (json.trim().equals("null")) return null;
        try {
            DataObject rule = DataObject.fromJson(json);
            for (String key : rule.keys()) {
                if (!WRITABLE.contains(key)) throw new IllegalArgumentException("Unsupported recurrence field: " + key);
            }
            int frequency = integer(rule.toMap().get("frequency"), "frequency", 0, 3);
            int interval = rule.hasKey("interval") ? integer(rule.toMap().get("interval"), "interval", 1, 2) : 1;
            if (interval != 1 && frequency != 2) throw new IllegalArgumentException("Only weekly rules support interval 2");
            rule.put("interval", interval);
            OffsetDateTime start = OffsetDateTime.parse(effectiveStart);
            OffsetDateTime anchor = rule.hasKey("start") ? OffsetDateTime.parse(rule.getString("start")) : start;
            if (!anchor.toInstant().equals(start.toInstant())) throw new IllegalArgumentException("Recurrence anchor must match event start");
            rule.put("start", start.toString());
            var utc = start.withOffsetSameInstant(ZoneOffset.UTC);
            int day = utc.getDayOfWeek().getValue() - 1;
            int families = (present(rule, "by_weekday") ? 1 : 0) + (present(rule, "by_n_weekday") ? 1 : 0)
                    + (present(rule, "by_month") || present(rule, "by_month_day") ? 1 : 0);
            if (families > 1) throw new IllegalArgumentException("Recurrence selectors are mutually exclusive");
            if (frequency == 2 || frequency == 3) {
                if (present(rule, "by_n_weekday") || present(rule, "by_month") || present(rule, "by_month_day")) {
                    throw new IllegalArgumentException("Daily/weekly rules require weekday selectors");
                }
                if (!present(rule, "by_weekday") && frequency == 2) rule.put("by_weekday", List.of(day));
                if (present(rule, "by_weekday")) {
                    List<Integer> days = numbers(rule.getArray("by_weekday"), "by_weekday", 0, 6);
                    if ((frequency == 2 && days.size() != 1) || !days.contains(day)) {
                        throw new IllegalArgumentException("Recurrence weekdays must match the start");
                    }
                }
            } else if (frequency == 1) {
                if (present(rule, "by_weekday") || present(rule, "by_month") || present(rule, "by_month_day")) {
                    throw new IllegalArgumentException("Monthly rules require an nth-weekday selector");
                }
                int nth = (utc.getDayOfMonth() - 1) / 7 + 1;
                if (!present(rule, "by_n_weekday")) {
                    rule.put("by_n_weekday", List.of(java.util.Map.of("n", nth, "day", day)));
                }
                DataArray values = rule.getArray("by_n_weekday");
                if (values.length() != 1) throw new IllegalArgumentException("Use one monthly selector");
                DataObject value = values.getObject(0);
                if (!value.keys().equals(Set.of("n", "day"))
                        || integer(value.toMap().get("n"), "n", 1, 5) != nth
                        || integer(value.toMap().get("day"), "day", 0, 6) != day) {
                    throw new IllegalArgumentException("Monthly selector must match the start");
                }
            } else {
                if (present(rule, "by_weekday") || present(rule, "by_n_weekday")) {
                    throw new IllegalArgumentException("Yearly rules require month and day selectors");
                }
                if (!present(rule, "by_month") && !present(rule, "by_month_day")) {
                    rule.put("by_month", List.of(utc.getMonthValue())).put("by_month_day", List.of(utc.getDayOfMonth()));
                }
                if (!numbers(rule.getArray("by_month"), "by_month", 1, 12).equals(List.of(utc.getMonthValue()))
                        || !numbers(rule.getArray("by_month_day"), "by_month_day", 1, 31).equals(List.of(utc.getDayOfMonth()))) {
                    throw new IllegalArgumentException("Yearly selector must match the start");
                }
            }
            return rule;
        } catch (IllegalArgumentException e) { throw e; }
        catch (RuntimeException e) { throw new IllegalArgumentException("Invalid recurrenceRule JSON or fields", e); }
    }

    static DataObject move(DataObject saved, String start) {
        DataObject rule = DataObject.empty();
        for (String key : saved.keys()) {
            if (WRITABLE.contains(key)) rule.put(key, saved.toMap().get(key));
            else if (!READ_ONLY.contains(key)) throw new IllegalArgumentException("Unreadable recurrence field: " + key);
        }
        int frequency = integer(rule.toMap().get("frequency"), "frequency", 0, 3);
        rule.put("start", start);
        if (frequency == 2) rule.remove("by_weekday");
        if (frequency == 1) rule.remove("by_n_weekday");
        if (frequency == 0) { rule.remove("by_month"); rule.remove("by_month_day"); }
        return parse(rule.toString(), start);
    }

    private static boolean present(DataObject rule, String field) {
        return rule.hasKey(field) && !rule.isNull(field);
    }

    private static List<Integer> numbers(DataArray array, String field, int min, int max) {
        List<Integer> values = array.toList().stream().map(value -> integer(value, field, min, max)).toList();
        if (values.isEmpty() || new HashSet<>(values).size() != values.size()) {
            throw new IllegalArgumentException(field + " must contain distinct values");
        }
        return values;
    }

    private static int integer(Object value, String field, int min, int max) {
        if (!(value instanceof Number)) throw new IllegalArgumentException(field + " must be an integer");
        try {
            int number = new BigDecimal(value.toString()).intValueExact();
            if (number < min || number > max) throw new IllegalArgumentException(field + " out of range");
            return number;
        } catch (ArithmeticException e) { throw new IllegalArgumentException(field + " must be an integer", e); }
    }
}
