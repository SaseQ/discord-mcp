package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.util.List;

/**
 * Validation and rendering for a guild scheduled event's {@code recurrence_rule}.
 *
 * <p>JDA cannot express this field at all — no class in JDA 6.4.1 mentions recurrence — so the
 * rule is built as raw JSON and sent through a custom route. See {@link ScheduledEventService}.
 *
 * <p>Discord accepts a much narrower set of rules than the field list suggests, and rejects the
 * rest with an opaque 400. Validating here turns "Discord said no" into a message that says which
 * constraint was broken.
 */
public final class RecurrenceRule {

    public static final int YEARLY = 0;
    public static final int MONTHLY = 1;
    public static final int WEEKLY = 2;
    public static final int DAILY = 3;

    /** Fields Discord documents as not settable by an API client. */
    private static final List<String> REJECTED = List.of("count", "end", "by_year_day");

    /** Everything a client is allowed to send. Anything else is Discord's to populate. */
    private static final List<String> WRITABLE =
            List.of("frequency", "interval", "start", "by_weekday", "by_n_weekday", "by_month", "by_month_day");

    private static final String[] FREQ_NAMES = {"yearly", "monthly", "weekly", "daily"};
    private static final String[] DAY_NAMES = {
            "Monday", "Tuesday", "Wednesday", "Thursday", "Friday", "Saturday", "Sunday"};

    private RecurrenceRule() {
    }

    /**
     * Parse and validate a caller-supplied recurrence rule.
     *
     * @param json  the rule as a JSON object
     * @param start ISO8601 start for the recurrence interval, used when the rule omits one
     * @return the validated rule, ready to send
     */
    public static DataObject parse(String json, String start) {
        DataObject rule;
        try {
            rule = DataObject.fromJson(json);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "recurrenceRule must be a JSON object, for example "
                            + "{\"frequency\": 2, \"interval\": 1, \"by_weekday\": [2]} for weekly on Wednesday. "
                            + "Parse error: " + e.getMessage());
        }

        for (String field : REJECTED) {
            if (rule.hasKey(field)) {
                throw new IllegalArgumentException(
                        "recurrence_rule." + field + " cannot be set by an API client. Discord "
                                + "populates it. Remove it from the rule.");
            }
        }

        if (!rule.hasKey("frequency")) {
            throw new IllegalArgumentException(
                    "recurrence_rule.frequency is required: 0=yearly, 1=monthly, 2=weekly, 3=daily");
        }
        int frequency = rule.getInt("frequency");
        if (frequency < 0 || frequency > 3) {
            throw new IllegalArgumentException(
                    "recurrence_rule.frequency must be 0 (yearly), 1 (monthly), 2 (weekly), or 3 (daily)");
        }

        int interval = rule.hasKey("interval") ? rule.getInt("interval") : 1;
        if (interval < 1) {
            throw new IllegalArgumentException("recurrence_rule.interval must be at least 1");
        }
        if (interval > 1 && frequency != WEEKLY) {
            throw new IllegalArgumentException(
                    "recurrence_rule.interval may only exceed 1 for weekly events (frequency 2), "
                            + "which is how Discord expresses every-other-week.");
        }
        if (interval > 2) {
            // Discord documents weekly interval as 1 or 2 only — 2 being every-other-week. A
            // higher value is not "unusual but allowed", it is rejected, and letting it through
            // create() would leave a stray non-recurring event behind.
            throw new IllegalArgumentException(
                    "recurrence_rule.interval must be 1 or 2. Discord only supports every week or "
                            + "every other week; there is no every-Nth-week rule.");
        }
        rule.put("interval", interval);

        boolean hasWeekday = hasArray(rule, "by_weekday");
        boolean hasNWeekday = hasArray(rule, "by_n_weekday");
        boolean hasMonth = hasArray(rule, "by_month");
        boolean hasMonthDay = hasArray(rule, "by_month_day");

        if (hasWeekday && hasNWeekday) {
            throw new IllegalArgumentException(
                    "recurrence_rule.by_weekday and by_n_weekday are mutually exclusive");
        }
        if ((hasWeekday || hasNWeekday) && (hasMonth || hasMonthDay)) {
            throw new IllegalArgumentException(
                    "recurrence_rule.by_month/by_month_day cannot be combined with by_weekday or by_n_weekday");
        }

        if (hasWeekday) {
            if (frequency != WEEKLY && frequency != DAILY) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_weekday is only valid for weekly (2) or daily (3) events");
            }
            DataArray days = rule.getArray("by_weekday");
            if (frequency == WEEKLY && days.length() != 1) {
                throw new IllegalArgumentException(
                        "A weekly recurrence_rule.by_weekday must contain exactly one day. Discord does "
                                + "not accept multi-day weekly rules; use frequency 3 (daily) with a known "
                                + "weekday set instead.");
            }
            for (int i = 0; i < days.length(); i++) {
                int day = days.getInt(i);
                if (day < 0 || day > 6) {
                    throw new IllegalArgumentException(
                            "recurrence_rule.by_weekday values are 0=Monday through 6=Sunday, got " + day);
                }
            }
        }

        if (hasNWeekday) {
            if (frequency != MONTHLY) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday is only valid for monthly events (frequency 1)");
            }
            if (rule.getArray("by_n_weekday").length() != 1) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday must contain exactly one entry");
            }
        }

        if (hasMonth || hasMonthDay) {
            if (frequency != YEARLY) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_month and by_month_day are only valid for yearly events (frequency 0)");
            }
            if (!hasMonth || !hasMonthDay) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_month and by_month_day must be supplied together");
            }
            if (rule.getArray("by_month").length() != 1 || rule.getArray("by_month_day").length() != 1) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_month and by_month_day must each contain exactly one value");
            }
        }

        if (!rule.hasKey("start") || rule.getString("start", "").isEmpty()) {
            if (start == null || start.isEmpty()) {
                throw new IllegalArgumentException(
                        "recurrence_rule.start is required and could not be defaulted from the event start time");
            }
            rule.put("start", start);
        }
        return rule;
    }

    private static boolean hasArray(DataObject rule, String key) {
        return rule.hasKey(key) && !rule.isNull(key) && rule.getArray(key).length() > 0;
    }

    /**
     * Rebuild a rule Discord sent us into one we are allowed to send back.
     *
     * <p>A GET returns the server-owned fields too — {@code count}, {@code end},
     * {@code by_year_day} — and echoing them on a PATCH is rejected. That matters most in the one
     * case this class exists for: moving a recurring event's anchor starts from the existing rule.
     *
     * @param serverRule a recurrence rule as returned by Discord
     * @param start      the new anchor
     * @return a validated, writable rule
     */
    public static DataObject withStart(DataObject serverRule, String start) {
        DataObject writable = DataObject.empty();
        for (String field : WRITABLE) {
            if (serverRule.hasKey(field) && !serverRule.isNull(field)) {
                writable.put(field, serverRule.get(field));
            }
        }
        writable.put("start", start);
        // Round-trip through the same validation the caller's input gets, so we can never send
        // ourselves something we would have rejected from anyone else.
        return parse(writable.toString(), start);
    }

    /** One-line human summary, so a recurring event is visibly recurring in tool output. */
    public static String describe(DataObject rule) {
        if (rule == null) {
            return null;
        }
        int frequency = rule.getInt("frequency", -1);
        if (frequency < 0 || frequency > 3) {
            return "recurring (unrecognised frequency)";
        }
        int interval = rule.getInt("interval", 1);
        StringBuilder sb = new StringBuilder();
        sb.append(interval > 1 ? "every " + interval + " weeks" : FREQ_NAMES[frequency]);

        if (hasArray(rule, "by_weekday")) {
            DataArray days = rule.getArray("by_weekday");
            StringBuilder names = new StringBuilder();
            for (int i = 0; i < days.length(); i++) {
                int day = days.getInt(i);
                if (i > 0) names.append(", ");
                names.append(day >= 0 && day <= 6 ? DAY_NAMES[day] : String.valueOf(day));
            }
            sb.append(" on ").append(names);
        }
        if (hasArray(rule, "by_month_day")) {
            sb.append(" on day ").append(rule.getArray("by_month_day").getInt(0));
            if (hasArray(rule, "by_month")) {
                sb.append(" of month ").append(rule.getArray("by_month").getInt(0));
            }
        }
        if (hasArray(rule, "by_n_weekday")) {
            DataObject nth = rule.getArray("by_n_weekday").getObject(0);
            int day = nth.getInt("day", -1);
            sb.append(" on occurrence ").append(nth.getInt("n", 0))
                    .append(" of ").append(day >= 0 && day <= 6 ? DAY_NAMES[day] : String.valueOf(day));
        }
        String start = rule.getString("start", null);
        if (start != null && !start.isEmpty()) {
            sb.append(", anchored at ").append(start);
        }
        return sb.toString();
    }
}
