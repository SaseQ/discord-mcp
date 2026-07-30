package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.requests.RestActionImpl;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.stream.Collectors;

@Service
public class ScheduledEventService {

    private final JDA jda;

    static final String RECURRENCE_PARAM =
            "Recurrence rule as JSON. {\"frequency\": 2} is usually all you need: it recurs weekly "
                    + "on whatever the start time falls on. frequency: 0=yearly, 1=monthly, 2=weekly, 3=daily. "
                    + "by_weekday: 0=Monday..6=Sunday, and a weekly rule accepts exactly one day. "
                    + "IMPORTANT: Discord evaluates recurrence against the UTC date of the start time, "
                    + "not its local date, so a 22:00 US-Eastern event recurs on the FOLLOWING weekday. "
                    + "Prefer to omit the selector: for weekly, monthly and yearly it is derived from the "
                    + "start time correctly, and a supplied one must match the UTC date or it is rejected. "
                    + "interval may exceed 1 only for weekly (every-other-week). "
                    + "by_n_weekday is monthly only; by_month with by_month_day is yearly only. "
                    + "count, end and by_year_day are set by Discord and must be omitted. "
                    + "Defaults its start to the event's start time. "
                    + "On edit, pass \"null\" to remove recurrence and make the event a one-off; "
                    + "omitting this parameter leaves any existing recurrence untouched.";

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    public ScheduledEventService(JDA jda) {
        this.jda = jda;
    }

    private String resolveGuildId(String guildId) {
        if ((guildId == null || guildId.isEmpty()) && defaultGuildId != null && !defaultGuildId.isEmpty()) {
            return defaultGuildId;
        }
        return guildId;
    }

    private Guild getGuild(String guildId) {
        guildId = resolveGuildId(guildId);
        if (guildId == null || guildId.isEmpty()) {
            throw new IllegalArgumentException("guildId cannot be null");
        }
        Guild guild = jda.getGuildById(guildId);
        if (guild == null) {
            throw new IllegalArgumentException("Discord server not found by guildId");
        }
        return guild;
    }

    private ScheduledEvent getEvent(Guild guild, String eventId) {
        if (eventId == null || eventId.isEmpty()) {
            throw new IllegalArgumentException("eventId cannot be null");
        }
        ScheduledEvent event = guild.getScheduledEventById(eventId);
        if (event == null) {
            throw new IllegalArgumentException("Scheduled event not found by eventId");
        }
        return event;
    }

    private OffsetDateTime parseTime(String time) {
        try {
            return OffsetDateTime.parse(time);
        } catch (DateTimeParseException e) {
            throw new IllegalArgumentException("Invalid ISO8601 timestamp: " + time);
        }
    }

    /**
     * Read a scheduled event as raw JSON.
     *
     * <p>JDA has no representation for {@code recurrence_rule}, so a recurring event is
     * indistinguishable from a one-off through the normal entity. Routed through JDA's own request
     * stack rather than a separate HTTP client, so it shares the bot token, the rate limiter, and
     * the retry behaviour instead of quietly becoming an unmetered second path to Discord.
     */
    private DataObject fetchRaw(String guildId, String eventId) {
        Route.CompiledRoute route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(guildId, eventId);
        return new RestActionImpl<DataObject>(jda, route,
                (response, request) -> response.getObject()).complete();
    }

    private DataObject patchRaw(String guildId, String eventId, DataObject body) {
        Route.CompiledRoute route = Route.custom(Method.PATCH, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(guildId, eventId);
        return new RestActionImpl<DataObject>(jda, route, body,
                (response, request) -> response.getObject()).complete();
    }

    /** The event's recurrence rule, or null if it is not a recurring event. */
    private DataObject recurrenceOf(DataObject raw) {
        // Tolerates an absent key as well as an explicit null, so it is safe to call with the empty
        // object used when a best-effort read failed.
        return !raw.hasKey("recurrence_rule") || raw.isNull("recurrence_rule")
                ? null
                : raw.getObject("recurrence_rule");
    }

    /**
     * Apply a recurrence change, reporting precisely what already landed if it fails.
     *
     * <p>The ordinary fields are written by JDA's manager before this runs, so a failure here
     * leaves a half-applied edit. Saying which half is the difference between a caller who can fix
     * it and one who has to go and look. Creation can compensate by deleting the event it just
     * made; an edit has nothing equivalent to undo, and an honest report beats a rollback that
     * would itself be a second fallible write.
     */
    private void patchRecurrence(Guild guild, ScheduledEvent event, DataObject body, String applied) {
        try {
            patchRaw(guild.getId(), event.getId(), body);
        } catch (RuntimeException e) {
            // A thrown request does not prove the change did not happen — a lost response after
            // Discord processed the PATCH looks identical from here. Rather than assert an outcome
            // we cannot know, read the event back and report what is actually true.
            String outcome;
            try {
                DataObject after = fetchRaw(guild.getId(), event.getId());
                DataObject rule = recurrenceOf(after);
                outcome = rule == null
                        ? " The event is currently not recurring."
                        : " The event currently recurs: " + RecurrenceRule.describe(rule) + ".";
            } catch (RuntimeException unverifiable) {
                outcome = " Could not read the event back, so whether the recurrence change applied is"
                        + " unknown — check the event before retrying.";
            }
            throw new IllegalArgumentException(
                    "The recurrence change failed: " + e.getMessage() + ". "
                            + (applied.isEmpty()
                            ? "Nothing else was changed."
                            : "These changes were already applied and remain in effect: " + applied + ".")
                            + outcome);
        }
    }

    /** Human list of the fields JDA's manager has already written. */
    private String describeApplied(String name, String description, String scheduledStartTime,
                                   String location, Integer statusCode) {
        List<String> parts = new java.util.ArrayList<>();
        if (name != null && !name.isEmpty()) parts.add("name");
        if (description != null && !description.isEmpty()) parts.add("description");
        if (scheduledStartTime != null && !scheduledStartTime.isEmpty()) parts.add("start time");
        if (location != null && !location.isEmpty()) parts.add("location");
        if (statusCode != null) parts.add("status");
        return String.join(", ", parts);
    }

    /**
     * Whether two ISO8601 strings denote the same moment.
     *
     * <p>String equality is wrong here. Discord normalises the timestamps it returns, so the value
     * from a raw GET routinely differs textually from the one the caller sent for the same instant
     * — {@code 2026-08-06T01:00:00+00:00} against {@code 2026-08-05T20:00:00-05:00}. Comparing text
     * would reject correct input.
     */
    private boolean sameInstant(String a, String b) {
        if (a == null || b == null) {
            return false;
        }
        try {
            return OffsetDateTime.parse(a).toInstant().equals(OffsetDateTime.parse(b).toInstant());
        } catch (DateTimeParseException e) {
            return a.equals(b);
        }
    }

    /**
     * Parse the status parameter once, so the terminal-transition guard and the setter can never
     * disagree about what the caller asked for.
     *
     * @return the status code, or null when no status change was requested
     */
    private Integer parseStatus(String status) {
        if (status == null || status.isBlank()) {
            return null;
        }
        int code;
        try {
            code = Integer.parseInt(status.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled), got: " + status);
        }
        if (code < 1 || code > 4) {
            throw new IllegalArgumentException(
                    "status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled), got: " + code);
        }
        return code;
    }

    /** Whether two rules pick the same point in the cycle, ignoring the anchor timestamp. */
    private boolean sameSelectors(DataObject a, DataObject b) {
        for (String selector : List.of("by_weekday", "by_n_weekday", "by_month", "by_month_day")) {
            boolean hasLeft = a.hasKey(selector) && !a.isNull(selector);
            boolean hasRight = b.hasKey(selector) && !b.isNull(selector);
            if (hasLeft != hasRight) {
                return false;
            }
            if (!hasLeft) {
                continue;
            }
            if (selector.equals("by_n_weekday")) {
                // Field by field, not serialised text: Discord may return {"day":2,"n":1} where we
                // build {"n":1,"day":2}, and reporting that as a schedule change would be a false
                // alarm on the loudest message this tool produces.
                DataObject left = a.getArray(selector).getObject(0);
                DataObject right = b.getArray(selector).getObject(0);
                if (left.getInt("n", -1) != right.getInt("n", -1)
                        || left.getInt("day", -1) != right.getInt("day", -1)) {
                    return false;
                }
                continue;
            }
            if (!a.getArray(selector).toString().equals(b.getArray(selector).toString())) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether the caller is asking to turn a recurring event back into a one-off.
     *
     * <p>Needs its own spelling because an absent parameter already means "leave recurrence alone",
     * so there is no way to express {@code recurrence_rule: null} otherwise.
     */
    private boolean isClearRequest(String recurrenceRule) {
        if (recurrenceRule == null) {
            return false;
        }
        String trimmed = recurrenceRule.trim();
        return trimmed.equalsIgnoreCase("null") || trimmed.equalsIgnoreCase("none");
    }

    private String formatEvent(ScheduledEvent event) {
        StringBuilder sb = new StringBuilder();
        sb.append("**").append(event.getName()).append("** (ID: ").append(event.getId()).append(")\n");
        sb.append("  • Type: ").append(event.getType()).append("\n");
        sb.append("  • Status: ").append(event.getStatus()).append("\n");
        sb.append("  • Start: ").append(event.getStartTime());
        if (event.getEndTime() != null) sb.append("\n  • End: ").append(event.getEndTime());
        if (event.getChannel() != null) {
            sb.append("\n  • Channel: ").append(event.getChannel().getName())
                    .append(" (ID: ").append(event.getChannel().getId()).append(")");
        }
        if (event.getLocation() != null && !event.getLocation().isEmpty()) {
            sb.append("\n  • Location: ").append(event.getLocation());
        }
        if (event.getDescription() != null && !event.getDescription().isEmpty()) {
            sb.append("\n  • Description: ").append(event.getDescription());
        }
        sb.append("\n  • Interested: ").append(event.getInterestedUserCount()).append(" users");
        return sb.toString();
    }

    @Tool(name = "create_guild_scheduled_event", description = "Schedule a new event on the server (voice, stage, or external)")
    public String createScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Name of the event") String name,
            @ToolParam(description = "Description of the event", required = false) String description,
            @ToolParam(description = "ISO8601 timestamp for when the event starts") String scheduledStartTime,
            @ToolParam(description = "ISO8601 timestamp for when the event ends (Required for External events)", required = false) String scheduledEndTime,
            @ToolParam(description = "Type of event: 1=Stage Instance, 2=Voice, 3=External") String entityType,
            @ToolParam(description = "Channel ID (Required for types 1 and 2)", required = false) String channelId,
            @ToolParam(description = "Location or link (Required for type 3 - External)", required = false) String location,
            @ToolParam(description = RECURRENCE_PARAM, required = false) String recurrenceRule) {

        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be null");
        if (entityType == null || entityType.isEmpty()) throw new IllegalArgumentException("entityType cannot be null");

        int type = Integer.parseInt(entityType);
        OffsetDateTime startTime = parseTime(scheduledStartTime);

        var action = switch (type) {
            case 1, 2 -> {
                if (channelId == null || channelId.isEmpty())
                    throw new IllegalArgumentException("channelId is required for Stage and Voice events");
                GuildChannel channel = guild.getGuildChannelById(channelId);
                if (channel == null) throw new IllegalArgumentException("Channel not found by channelId");
                yield guild.createScheduledEvent(name, channel, startTime);
            }
            case 3 -> {
                if (location == null || location.isEmpty())
                    throw new IllegalArgumentException("location is required for External events");
                if (scheduledEndTime == null || scheduledEndTime.isEmpty())
                    throw new IllegalArgumentException("scheduledEndTime is required for External events");
                yield guild.createScheduledEvent(name, location, startTime, parseTime(scheduledEndTime));
            }
            default -> throw new IllegalArgumentException("entityType must be 1 (Stage), 2 (Voice), or 3 (External)");
        };

        if (description != null && !description.isEmpty()) action.setDescription(description);
        if (type != 3 && scheduledEndTime != null && !scheduledEndTime.isEmpty()) {
            action.setEndTime(parseTime(scheduledEndTime));
        }

        // Validate the rule BEFORE creating anything. Otherwise a bad rule leaves a stray
        // non-recurring event behind that the caller then has to notice and clean up.
        DataObject rule = (recurrenceRule != null && !recurrenceRule.isEmpty())
                ? RecurrenceRule.parse(recurrenceRule, scheduledStartTime)
                : null;
        // Same disagreement the edit path refuses: an event created at one time whose series is
        // anchored at another follows the anchor, or fails the PATCH and leaves a stray one-off.
        if (rule != null && !sameInstant(rule.getString("start", null), scheduledStartTime)) {
            throw new IllegalArgumentException(
                    "scheduledStartTime is " + scheduledStartTime + " but the recurrence rule anchors at "
                            + rule.getString("start", "") + ". They must match. Omit start from the rule to "
                            + "have it default to the event's start time.");
        }

        ScheduledEvent event = action.complete();
        String formatted = formatEvent(event);

        if (rule != null) {
            // JDA's create action has no recurrence setter, so the field is applied as a follow-up
            // PATCH on the event it just made. That leaves a window: local validation cannot
            // predict a transient REST failure or a guild-level rejection, and without cleanup the
            // caller is left with a one-off event they did not ask for, plus a thrown error that
            // invites a retry and a duplicate.
            try {
                patchRaw(guild.getId(), event.getId(), DataObject.empty().put("recurrence_rule", rule));
            } catch (RuntimeException e) {
                try {
                    event.delete().complete();
                } catch (RuntimeException cleanupFailure) {
                    throw new IllegalArgumentException(
                            "Failed to apply the recurrence rule (" + e.getMessage() + ") and could not remove "
                                    + "the event created for it (ID: " + event.getId() + "). Delete it manually.");
                }
                throw new IllegalArgumentException(
                        "Failed to apply the recurrence rule, so the event was not created: " + e.getMessage());
            }
            formatted += "\n  • Recurrence: " + RecurrenceRule.describe(rule);
        }
        return "Created scheduled event:\n" + formatted;
    }

    @Tool(name = "edit_guild_scheduled_event", description = "Modify details of an existing event or change its status (start, complete, cancel)")
    public String editScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "New status: 1=Scheduled, 2=Active (start), 3=Completed, 4=Canceled", required = false) String status,
            @ToolParam(description = "New name", required = false) String name,
            @ToolParam(description = "New description", required = false) String description,
            @ToolParam(description = "New ISO8601 start time. If the event recurs, its recurrence anchor is moved to match, so the series actually changes rather than snapping back.", required = false) String scheduledStartTime,
            @ToolParam(description = "New location (for External events)", required = false) String location,
            @ToolParam(description = RECURRENCE_PARAM, required = false) String recurrenceRule) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);

        // Read the live event before touching it: JDA cannot tell us whether this is a recurring
        // series, and that changes what a start-time edit means.
        boolean movingStart = scheduledStartTime != null && !scheduledStartTime.isEmpty();
        // Needed only when recurrence is actually in play: to know whether a start move is also a
        // series move, or whether there is a rule to clear. For a plain rename it feeds nothing but
        // an informational line, and a transient failure on this route must not stop an otherwise
        // independent edit from being attempted at all.
        boolean recurrenceRelevant = movingStart
                || (recurrenceRule != null && !recurrenceRule.isEmpty());
        DataObject raw;
        boolean recurrenceReadFailed = false;
        String recurrenceReadError = null;
        try {
            raw = fetchRaw(guild.getId(), event.getId());
        } catch (RuntimeException e) {
            if (recurrenceRelevant) {
                throw new IllegalArgumentException(
                        "Could not read the event's current recurrence, which this edit depends on"
                                + (e.getMessage() == null ? "" : ": " + e.getMessage())
                                + ". Nothing was changed.");
            }
            raw = DataObject.empty();
            recurrenceReadFailed = true;
            recurrenceReadError = e.getMessage();
        }
        DataObject existingRecurrence = recurrenceOf(raw);

        // Validate the recurrence BEFORE anything is persisted. manager.complete() below is not
        // undoable, so parsing afterwards would report failure on a request that had already
        // applied the name/description/time half of the edit.
        boolean clearingRecurrence = isClearRequest(recurrenceRule);
        DataObject newRule = null;
        if (recurrenceRule != null && !recurrenceRule.isEmpty() && !clearingRecurrence) {
            String anchor = movingStart ? scheduledStartTime : raw.getString("scheduled_start_time", null);
            newRule = RecurrenceRule.parse(recurrenceRule, anchor);
            // A rule may carry its own start, which parse() keeps. If that disagrees with the
            // event's start time the series follows the anchor and the event time is ignored —
            // the snap-back this tool promises to prevent. Checked against the effective start
            // whether or not this call is moving it: a recurrence-only edit can drag the anchor
            // away from an unchanged scheduled_start_time just as easily.
            if (!sameInstant(newRule.getString("start", null), anchor)) {
                throw new IllegalArgumentException(
                        "The event starts at " + anchor + " but the recurrence rule anchors at "
                                + newRule.getString("start", "") + ". They must match, or the series would "
                                + "follow the anchor and ignore the event's start time. Omit start from the "
                                + "rule to have it default to the event's start time"
                                + (movingStart ? ", or set scheduledStartTime to match." : "."));
            }
        }
        if (clearingRecurrence && existingRecurrence == null) {
            throw new IllegalArgumentException(
                    "This event is not recurring, so there is no recurrence rule to clear.");
        }

        // Completing or cancelling is irreversible and leaves an event that cannot then be
        // modified. manager.complete() runs before the recurrence PATCH, so this combination would
        // apply the terminal transition, fail the recurrence write, and report overall failure on
        // a change that had already half happened and cannot be undone.
        // Parsed once and reused below. Comparing the raw string here while applying
        // Integer.parseInt later meant "03" or "+3" read as non-terminal to the guard and as
        // COMPLETED to the setter, which is precisely the combination the guard exists to stop.
        Integer statusCode = parseStatus(status);
        boolean terminalStatus = statusCode != null && (statusCode == 3 || statusCode == 4);
        // The implicit anchor move counts too: moving the start of a recurring event triggers a
        // recurrence PATCH even with no recurrenceRule supplied, and that write would land after
        // the terminal transition just the same.
        boolean touchesRecurrence = newRule != null || clearingRecurrence
                || (existingRecurrence != null && movingStart);
        if (terminalStatus && touchesRecurrence) {
            throw new IllegalArgumentException(
                    "Refusing to change recurrence while completing or cancelling this event. The status "
                            + "change cannot be undone and a terminal event cannot then be edited. Note that "
                            + "moving scheduledStartTime on a recurring event also changes its recurrence. "
                            + "Do the recurrence change first, or drop it from this call.");
        }

        var manager = event.getManager();
        if (name != null && !name.isEmpty()) manager.setName(name);
        if (description != null && !description.isEmpty()) manager.setDescription(description);
        if (movingStart) manager.setStartTime(parseTime(scheduledStartTime));
        if (location != null && !location.isEmpty()) manager.setLocation(location);
        if (statusCode != null) {
            manager.setStatus(switch (statusCode) {
                case 1 -> ScheduledEvent.Status.SCHEDULED;
                case 2 -> ScheduledEvent.Status.ACTIVE;
                case 3 -> ScheduledEvent.Status.COMPLETED;
                case 4 -> ScheduledEvent.Status.CANCELED;
                default -> throw new IllegalArgumentException("status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled)");
            });
        }

        manager.complete();
        StringBuilder result = new StringBuilder("Successfully updated scheduled event: ")
                .append(event.getName()).append(" (ID: ").append(event.getId()).append(")");

        // Everything above is already persisted. The recurrence write below is a separate request
        // and can still fail for reasons validation cannot foresee, so the failure has to say which
        // half landed. Creation can compensate by deleting the event it just made; an edit has
        // nothing equivalent to undo, and the honest report is worth more than a rollback that
        // would itself be a second fallible write.
        String applied = describeApplied(name, description, scheduledStartTime, location, statusCode);

        if (clearingRecurrence) {
            patchRecurrence(guild, event, DataObject.empty().putNull("recurrence_rule"), applied);
            result.append("\n  • Recurrence removed. This is now a one-off event.");
        } else if (newRule != null) {
            patchRecurrence(guild, event, DataObject.empty().put("recurrence_rule", newRule), applied);
            result.append("\n  • Recurrence set: ").append(RecurrenceRule.describe(newRule));
        } else if (existingRecurrence != null && movingStart) {
            // The bug this tool used to have. A recurring event's series is anchored by
            // recurrence_rule.start, not by scheduled_start_time. Moving only the latter shifts the
            // next occurrence and then the series snaps back to its old time, while the tool
            // reported plain success. Move the anchor with it and say so.
            //
            // withStart rebuilds the rule from writable fields only: the GET that produced
            // existingRecurrence also returns count/end/by_year_day, which Discord owns and
            // rejects on the way back in.
            DataObject moved = RecurrenceRule.withStart(existingRecurrence, scheduledStartTime);
            patchRecurrence(guild, event, DataObject.empty().put("recurrence_rule", moved), applied);
            result.append("\n  • This is a recurring event, so its recurrence anchor was moved to ")
                    .append(scheduledStartTime)
                    .append(" as well. Without that the series would have snapped back to the old time.");
            String before = RecurrenceRule.describe(existingRecurrence);
            String after = RecurrenceRule.describe(moved);
            if (!sameSelectors(existingRecurrence, moved)) {
                // Changing which day a weekly class lands on is a bigger deal than a time shift,
                // and it happened as a side effect of the requested move. Say it loudly.
                result.append("\n  • The new date falls on a different part of the cycle, so the series now runs on a ")
                        .append("different schedule. Was: ").append(before).append(". Now: ").append(after)
                        .append(". Pass an explicit recurrenceRule if that is not what you wanted.");
            } else {
                result.append("\n  • Recurrence is now: ").append(after);
            }
        } else if (recurrenceReadFailed) {
            // listScheduledEvents refuses to let a failed read read as "nothing recurs", and this
            // path must not either: the note below is the one that stops a recurring event from
            // being edited as though it were a one-off.
            // Flagged by its own boolean rather than by the message being non-null: getMessage()
            // can return null, and keying off it made this note vanish entirely, rendering as
            // "does not recur" — the one thing the comment above says this path must never do.
            result.append("\n  • Note: this event's recurrence could not be read")
                    .append(recurrenceReadError == null ? "" : " (" + recurrenceReadError + ")")
                    .append(", so it may be a recurring event that is not reported here.");
        } else if (existingRecurrence != null) {
            result.append("\n  • Note: this is a recurring event (")
                    .append(RecurrenceRule.describe(existingRecurrence))
                    .append("). The recurrence rule was not changed.");
        }
        return result.toString();
    }

    @Tool(name = "delete_guild_scheduled_event", description = "Permanently delete a scheduled event")
    public String deleteScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);
        String eventName = event.getName();
        event.delete().complete();
        return "Successfully deleted scheduled event: " + eventName + " (ID: " + eventId + ")";
    }

    @Tool(name = "list_guild_scheduled_events", description = "List all active and scheduled events on the server")
    public String listScheduledEvents(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "Whether to include interested user count (default true)", required = false) String withUserCount) {

        Guild guild = getGuild(guildId);
        List<ScheduledEvent> events = guild.getScheduledEvents();

        if (events.isEmpty()) {
            return "No scheduled events found on this server.";
        }

        boolean includeUserCount = withUserCount == null || withUserCount.isEmpty() || Boolean.parseBoolean(withUserCount);

        // One raw list call so recurrence is visible here. Without it a weekly class and a one-off
        // look identical, which is how a recurring event gets edited as though it were not one.
        java.util.Map<String, DataObject> rules = new java.util.HashMap<>();
        boolean recurrenceKnown = false;
        try {
            Route.CompiledRoute route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events")
                    .compile(guild.getId());
            var raw = new RestActionImpl<net.dv8tion.jda.api.utils.data.DataArray>(jda, route,
                    (response, request) -> response.getArray()).complete();
            for (int i = 0; i < raw.length(); i++) {
                DataObject o = raw.getObject(i);
                DataObject rule = recurrenceOf(o);
                if (rule != null) {
                    rules.put(o.getString("id"), rule);
                }
            }
            recurrenceKnown = true;
        } catch (RuntimeException e) {
            // Recurrence detail is an enhancement to this listing, not its purpose, so losing it
            // must not turn a working list call into a failure. It must not silently read as
            // "nothing recurs" either — that is indistinguishable from the real thing.
            rules.clear();
        }

        String caveat = recurrenceKnown ? ""
                : "\n(Recurrence information could not be read, so no event below is marked as recurring"
                + " even if it is.)";
        return "Retrieved " + events.size() + " scheduled events:" + caveat + "\n" +
                events.stream()
                        .map(e -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("- **").append(e.getName()).append("** (ID: ").append(e.getId()).append(")\n");
                            sb.append("  • Type: ").append(e.getType()).append(" | Status: ").append(e.getStatus()).append("\n");
                            sb.append("  • Start: ").append(e.getStartTime());
                            if (e.getEndTime() != null) sb.append(" | End: ").append(e.getEndTime());
                            DataObject rule = rules.get(e.getId());
                            if (rule != null) sb.append("\n  • Recurs: ").append(RecurrenceRule.describe(rule));
                            if (includeUserCount) sb.append("\n  • Interested: ").append(e.getInterestedUserCount()).append(" users");
                            return sb.toString();
                        })
                        .collect(Collectors.joining("\n"));
    }

    @Tool(name = "get_guild_scheduled_event_users", description = "Get list of users interested in a scheduled event")
    public String getScheduledEventUsers(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "Max number of users to return (default 100)", required = false) String limit,
            @ToolParam(description = "Whether to include full member data with roles (default true)", required = false) String withMember) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);

        int maxResults = (limit != null && !limit.isEmpty()) ? Integer.parseInt(limit) : 100;
        boolean includeMember = withMember == null || withMember.isEmpty() || Boolean.parseBoolean(withMember);

        List<Member> members = event.retrieveInterestedMembers()
                .stream()
                .limit(maxResults)
                .toList();

        if (members.isEmpty()) {
            return "No interested users found for event: " + event.getName();
        }

        return "Retrieved " + members.size() + " interested users for event **" + event.getName() + "**:\n" +
                members.stream()
                        .map(m -> {
                            StringBuilder sb = new StringBuilder();
                            sb.append("- **").append(m.getUser().getName()).append("** (ID: ").append(m.getId()).append(")");
                            if (includeMember) {
                                String roles = m.getRoles().stream()
                                        .map(r -> r.getName() + " (" + r.getId() + ")")
                                        .collect(Collectors.joining(", "));
                                if (!roles.isEmpty()) sb.append("\n  • Roles: ").append(roles);
                                if (m.getNickname() != null) sb.append("\n  • Nickname: ").append(m.getNickname());
                            }
                            return sb.toString();
                        })
                        .collect(Collectors.joining("\n"));
    }
}
