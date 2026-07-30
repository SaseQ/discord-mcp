package dev.saseq.services;

import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.api.utils.data.DataObject;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Set;

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

    /** Weekday sets Discord accepts on a daily rule. It rejects arbitrary selections. */
    private static final List<Set<Integer>> DAILY_SETS = List.of(
            Set.of(0, 1, 2, 3, 4),              // weekdays
            Set.of(5, 6),                       // weekend
            Set.of(0, 1, 2, 3, 4, 5, 6));       // every day

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
                    "recurrenceRule must be a JSON object. {\"frequency\": 2} is usually all you need "
                            + "- it recurs weekly on whatever the start time falls on. "
                            + "Parse error: " + e.getMessage());
        }

        for (String field : REJECTED) {
            if (rule.hasKey(field)) {
                throw new IllegalArgumentException(
                        "recurrence_rule." + field + " cannot be set by an API client. Discord "
                                + "populates it. Remove it from the rule.");
            }
        }
        // Anything outside the writable schema is refused rather than forwarded. A near-miss such
        // as "by_weekdays" would otherwise sail through — daily needs no selector, so nothing else
        // would catch it — and Discord would either reject the PATCH after the event exists or
        // silently ignore the key and produce a different schedule than the one asked for.
        for (String key : rule.keys()) {
            if (!WRITABLE.contains(key)) {
                throw new IllegalArgumentException(
                        "recurrence_rule has an unrecognised field \"" + key + "\". Allowed fields are "
                                + String.join(", ", WRITABLE) + ".");
            }
        }

        if (!rule.hasKey("frequency") || rule.isNull("frequency")) {
            throw new IllegalArgumentException(
                    "recurrence_rule.frequency is required: 0=yearly, 1=monthly, 2=weekly, 3=daily");
        }
        requireWholeNumber(rule, "frequency", "recurrence_rule.frequency");
        int frequency = rule.getInt("frequency");
        if (frequency < 0 || frequency > 3) {
            throw new IllegalArgumentException(
                    "recurrence_rule.frequency must be 0 (yearly), 1 (monthly), 2 (weekly), or 3 (daily)");
        }

        boolean hasInterval = rule.hasKey("interval") && !rule.isNull("interval");
        if (hasInterval) {
            requireWholeNumber(rule, "interval", "recurrence_rule.interval");
        }
        int interval = hasInterval ? rule.getInt("interval") : 1;
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

        // An explicitly empty selector is not the same as an absent one. hasArray() treats both as
        // "not set", but an empty array still ships in the payload and Discord rejects it, so it
        // has to be caught here rather than skipped over.
        for (String selector : List.of("by_weekday", "by_n_weekday", "by_month", "by_month_day")) {
            if (rule.hasKey(selector) && !rule.isNull(selector) && arrayOf(rule, selector).isEmpty()) {
                throw new IllegalArgumentException(
                        "recurrence_rule." + selector + " is empty. Give it a value or omit the field entirely.");
            }
        }

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
            // Daily rules have no later selector comparison to catch a truncated value, so a set
            // like [0.9, 1.9, ...] would otherwise validate as weekdays and ship its fractions.
            requireWholeNumbers(rule, "by_weekday", "recurrence_rule.by_weekday");
            days = rule.getArray("by_weekday");
            java.util.TreeSet<Integer> set = new java.util.TreeSet<>();
            for (int i = 0; i < days.length(); i++) {
                int day = days.getInt(i);
                if (day < 0 || day > 6) {
                    throw new IllegalArgumentException(
                            "recurrence_rule.by_weekday values are 0=Monday through 6=Sunday, got " + day);
                }
                set.add(day);
            }
            if (frequency == DAILY && !DAILY_SETS.contains(set)) {
                // Discord accepts only "known sets" for daily rules rather than an arbitrary
                // selection of weekdays. The exact list is not enumerated in the docs, so this
                // allows the documented examples plus every day, and names them in the error.
                throw new IllegalArgumentException(
                        "A daily recurrence_rule.by_weekday must be a known weekday set: "
                                + "[0,1,2,3,4] weekdays, [5,6] weekend, or all seven days. "
                                + "For a single day use frequency 2 (weekly) instead.");
            }
        }

        if (hasNWeekday) {
            if (frequency != MONTHLY) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday is only valid for monthly events (frequency 1)");
            }
            DataArray entries = rule.getArray("by_n_weekday");
            if (entries.length() != 1) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday must contain exactly one entry");
            }
            DataObject nth;
            try {
                nth = entries.getObject(0);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday entries must be objects, "
                                + "for example [{\"n\": 2, \"day\": 4}]");
            }
            requireWholeNumber(nth, "n", "recurrence_rule.by_n_weekday n");
            requireWholeNumber(nth, "day", "recurrence_rule.by_n_weekday day");
            if (!nth.hasKey("n") || !nth.hasKey("day")) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday entries need both n and day, "
                                + "for example {\"n\": 2, \"day\": 4} for the second Friday");
            }
            int n = nth.getInt("n");
            if (n < 1 || n > 5) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday n must be 1 to 5 (which occurrence in the month), got " + n);
            }
            int day = nth.getInt("day");
            if (day < 0 || day > 6) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_n_weekday day must be 0=Monday through 6=Sunday, got " + day);
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
            requireWholeNumbers(rule, "by_month", "recurrence_rule.by_month");
            requireWholeNumbers(rule, "by_month_day", "recurrence_rule.by_month_day");
            int month = rule.getArray("by_month").getInt(0);
            if (month < 1 || month > 12) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_month must be 1 (January) through 12 (December), got " + month);
            }
            int monthDay = rule.getArray("by_month_day").getInt(0);
            if (monthDay < 1 || monthDay > 31) {
                throw new IllegalArgumentException(
                        "recurrence_rule.by_month_day must be 1 through 31, got " + monthDay);
            }
        }


        if (!rule.hasKey("start") || rule.getString("start", "").isEmpty()) {
            if (start == null || start.isEmpty()) {
                throw new IllegalArgumentException(
                        "recurrence_rule.start is required and could not be defaulted from the event start time");
            }
            rule.put("start", start);
        }
        // Checked whether supplied or defaulted. The event's own start time is validated
        // separately, so a rule carrying a malformed start of its own would otherwise create the
        // event and fail only on the recurrence PATCH.
        String anchor = rule.getString("start");
        OffsetDateTime moment;
        try {
            moment = OffsetDateTime.parse(anchor);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "recurrence_rule.start must be an ISO8601 timestamp, e.g. 2026-08-05T20:00:00-05:00, got: "
                            + anchor);
        }

        // The anchor is the series' first occurrence, so for weekly, monthly and yearly the
        // selector is fully determined by it: a Wednesday anchor with a Thursday by_weekday is not
        // a Thursday series, it is an incoherent rule. Supply it and it is checked; omit it and it
        // is filled in, because there is exactly one correct value and making the caller repeat it
        // only creates a way to get it wrong. Daily is exempt — its weekday set is a real choice.
        //
        // Derived through the same helper withStart uses, so the two can never disagree about what
        // a given anchor implies.
        DataObject expected = DataObject.empty();
        applySelectors(expected, frequency, moment);
        for (String selector : List.of("by_weekday", "by_n_weekday", "by_month", "by_month_day")) {
            if (!expected.hasKey(selector)) {
                continue;
            }
            String want = expected.getArray(selector).toString();
            if (!hasArray(rule, selector)) {
                rule.put(selector, expected.getArray(selector));
                continue;
            }
            String got = rule.getArray(selector).toString();
            if (selector.equals("by_n_weekday")) {
                // Compared field by field: JSON member order carries no meaning, so serialising
                // {"day":2,"n":1} and comparing text would reject a rule that is in fact identical.
                DataObject wantNth = expected.getArray(selector).getObject(0);
                DataObject gotNth = rule.getArray(selector).getObject(0);
                if (wantNth.getInt("n") == gotNth.getInt("n")
                        && wantNth.getInt("day") == gotNth.getInt("day")) {
                    continue;
                }
            }
            if (!want.equals(got)) {
                // Only blame the timezone when the anchor actually crosses a date boundary.
                // Otherwise the explanation is false and the advice is harmful: for a midday start
                // the caller almost certainly mistyped the date, and "omit the selector" would give
                // them the wrong series reported as success.
                boolean crossesDate = !moment.toLocalDate()
                        .equals(moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate());
                String omit = selector.startsWith("by_month") ? "by_month and by_month_day" : selector;
                throw new IllegalArgumentException(
                        "recurrence_rule." + selector + " is " + got + " but start " + anchor
                                + " is " + moment.withOffsetSameInstant(ZoneOffset.UTC).toLocalDate()
                                + " in UTC, which is " + want + ". "
                                + (crossesDate
                                ? "Discord evaluates recurrence against the UTC date, and this start crosses "
                                + "into the next day in UTC. The start time is almost certainly right - omit "
                                + omit + " and it is derived correctly."
                                : "The anchor is the first occurrence, so they must agree. Move start to the "
                                + "date you want, or omit " + omit + " to derive it from the start."));
            }
        }
        return rule;
    }

    /**
     * Reject a JSON number that is not a whole number.
     *
     * <p>{@code getInt} truncates silently, so 1.9 would validate as 1 while the fraction stayed in
     * the payload and went to Discord — an unsupported value quietly becoming a different schedule
     * rather than an error. Shared because this has now been missed on four separate fields.
     */
    private static void requireWholeNumber(DataObject holder, String key, String label) {
        // An explicit JSON null is handled here rather than left to the consumer's getInt, which
        // would raise a bare ParsingException that escapes parse() entirely. Callers for whom null
        // means "absent" test that themselves before calling.
        if (!holder.hasKey(key)) {
            return;
        }
        if (holder.isNull(key)) {
            throw new IllegalArgumentException(label + " must be a number, got null");
        }
        int value = wholeValueOf(holder.getString(key, ""), label);
        // Normalised, not just checked. Otherwise a whole-valued double or the string "2.0"
        // survives into the payload: the agreement check compares DataArray.toString(), so [3.0]
        // reads as disagreeing with [3], and getInt on "2.0" throws NumberFormatException naming
        // no field. interval has always been written back; everything else now is too.
        holder.put(key, value);
    }

    /** Array form, for the selectors whose elements are numbers. Normalises in place. */
    private static void requireWholeNumbers(DataObject holder, String key, String label) {
        DataArray values = arrayOf(holder, key);
        DataArray normalized = DataArray.empty();
        for (int i = 0; i < values.length(); i++) {
            String element = values.getString(i, null);
            if (element == null) {
                throw new IllegalArgumentException(label + " values must be numbers, got null");
            }
            normalized.add(wholeValueOf(element, label + " values"));
        }
        holder.put(key, normalized);
    }

    /**
     * Read a number exactly, reject it unless it is whole, and return it as an int.
     *
     * <p>{@link BigDecimal} rather than {@code double} because double loses exactly the cases that
     * matter: {@code "1e-400"} underflows to 0.0 and would pass as whole, silently becoming a
     * yearly rule, and {@code "2.0000000000000001"} rounds to 2. Both are then written back as if
     * the caller had asked for them.
     *
     * <p>This covers <em>quoted</em> spellings only. An unquoted JSON float is already a
     * {@code Double} by the time it reaches this class, because the rule is parsed by JDA's own
     * mapper without {@code USE_BIG_DECIMAL_FOR_FLOATS}, so the precision is gone before any check
     * here can see it. Closing that would mean parsing the caller's JSON separately rather than
     * reusing JDA's, which is more machinery than the remaining exposure justifies.
     */
    private static int wholeValueOf(String raw, String label) {
        BigDecimal value;
        try {
            value = new BigDecimal(raw.trim());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(label + " must be a number, got " + raw);
        }
        if (value.stripTrailingZeros().scale() > 0) {
            throw new IllegalArgumentException(label + " must be a whole number, got " + raw);
        }
        try {
            return value.intValueExact();
        } catch (ArithmeticException e) {
            throw new IllegalArgumentException(label + " is out of range, got " + raw);
        }
    }

    /**
     * Reads an array field, naming it if the caller sent something that is not one.
     *
     * <p>The example is per-key on purpose. A single hard-coded {@code [2]} sent a caller who put
     * an object where by_n_weekday's array belongs straight into a second failure about entry
     * shape — two round trips from a helper whose whole job is to show the right shape first time.
     */
    private static DataArray arrayOf(DataObject holder, String key) {
        try {
            return holder.getArray(key);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(
                    "recurrence_rule." + key + " must be an array, for example "
                            + (key.equals("by_n_weekday") ? "[{\"n\": 2, \"day\": 4}]" : "[2]"));
        }
    }

    private static boolean hasArray(DataObject rule, String key) {
        // arrayOf, not getArray: this is only reached after the empty-selector loop has already
        // guarded these four keys, and relying on that ordering means adding a fifth array
        // selector without adding it to that loop would reintroduce the bare parsing exception.
        return rule.hasKey(key) && !rule.isNull(key) && arrayOf(rule, key).length() > 0;
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

        // Moving the anchor is not enough on its own. A weekly rule selecting Wednesday, moved to
        // a Thursday, would keep by_weekday: [2] and contradict its own start — the series would
        // stay on Wednesday, or Discord would reject the rule, while the tool claimed the move
        // worked. That is the original bug in a different costume, so the selectors move too.
        OffsetDateTime moment;
        try {
            moment = OffsetDateTime.parse(start);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException(
                    "recurrence_rule.start must be an ISO8601 timestamp, got: " + start);
        }
        applySelectors(writable, writable.getInt("frequency", -1), moment);

        // Round-trip through the same validation the caller's input gets, so we can never send
        // ourselves something we would have rejected from anyone else.
        return parse(writable.toString(), start);
    }

    /**
     * Set the selectors a frequency requires from the moment the series is anchored at.
     *
     * <p>Shared so that rebuilding a rule and validating a caller's rule cannot disagree about
     * which weekday, month day, or nth-weekday a given anchor implies.
     */
    private static void applySelectors(DataObject target, int frequency, OffsetDateTime anchor) {
        // UTC, not the anchor's own offset. Confirmed against a live recurring event: an event at
        // 2026-06-26T02:00:00+00:00 — Thursday 22:00 in US Eastern, Friday in UTC — is stored by
        // Discord with by_weekday [4], Friday. Deriving from the local date would have produced
        // Thursday and put the series on the wrong day, and it also made two spellings of the same
        // instant yield different schedules.
        OffsetDateTime moment = anchor.withOffsetSameInstant(ZoneOffset.UTC);
        int weekday = moment.getDayOfWeek().getValue() - 1;   // java Monday=1, Discord Monday=0
        switch (frequency) {
            case WEEKLY -> target.put("by_weekday", DataArray.empty().add(weekday));
            case YEARLY -> target
                    .put("by_month", DataArray.empty().add(moment.getMonthValue()))
                    .put("by_month_day", DataArray.empty().add(moment.getDayOfMonth()));
            case MONTHLY -> target.put("by_n_weekday", DataArray.empty().add(
                    DataObject.empty()
                            // Which occurrence of that weekday the date falls on.
                            .put("n", ((moment.getDayOfMonth() - 1) / 7) + 1)
                            .put("day", weekday)));
            default -> {
                // Daily selects a set of weekdays, which the anchor date does not determine.
            }
        }
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
            sb.append(" on ").append(names).append(" (UTC)");
        }
        if (hasArray(rule, "by_month_day")) {
            sb.append(" on day ").append(rule.getArray("by_month_day").getInt(0));
            if (hasArray(rule, "by_month")) {
                sb.append(" of month ").append(rule.getArray("by_month").getInt(0));
            }
            sb.append(" (UTC)");
        }
        if (hasArray(rule, "by_n_weekday")) {
            DataObject nth = rule.getArray("by_n_weekday").getObject(0);
            int day = nth.getInt("day", -1);
            sb.append(" on occurrence ").append(nth.getInt("n", 0))
                    .append(" of ").append(day >= 0 && day <= 6 ? DAY_NAMES[day] : String.valueOf(day))
                    .append(" (UTC)");
        }
        String start = rule.getString("start", null);
        if (start != null && !start.isEmpty()) {
            // Normalised to UTC so the anchor agrees with the weekday above it. Left as-is, a
            // Thursday-in-UTC series reads "on Thursday (UTC), anchored at 2026-08-05T20:00-05:00"
            // — a Wednesday — which looks self-contradictory to the model reading it, and invites
            // exactly the wrong-day correction the mismatch error warns against.
            try {
                sb.append(", anchored at ")
                        .append(OffsetDateTime.parse(start).withOffsetSameInstant(ZoneOffset.UTC));
            } catch (DateTimeParseException e) {
                sb.append(", anchored at ").append(start);
            }
        }
        return sb.toString();
    }
}
