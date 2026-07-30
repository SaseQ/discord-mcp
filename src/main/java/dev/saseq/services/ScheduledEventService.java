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
            "Recurrence rule as JSON, e.g. {\"frequency\": 2, \"interval\": 1, \"by_weekday\": [2]} "
                    + "for weekly on Wednesday. frequency: 0=yearly, 1=monthly, 2=weekly, 3=daily. "
                    + "by_weekday: 0=Monday..6=Sunday, and a weekly rule accepts exactly one day. "
                    + "interval may exceed 1 only for weekly (every-other-week). "
                    + "by_n_weekday is monthly only; by_month with by_month_day is yearly only. "
                    + "count, end and by_year_day are set by Discord and must be omitted. "
                    + "Defaults its start to the event's start time.";

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
        return raw.isNull("recurrence_rule") ? null : raw.getObject("recurrence_rule");
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

        ScheduledEvent event = action.complete();
        String formatted = formatEvent(event);

        if (rule != null) {
            // JDA's create action has no recurrence setter, so the field is applied as a follow-up
            // PATCH on the event it just made.
            patchRaw(guild.getId(), event.getId(), DataObject.empty().put("recurrence_rule", rule));
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
        DataObject raw = fetchRaw(guild.getId(), event.getId());
        DataObject existingRecurrence = recurrenceOf(raw);
        boolean movingStart = scheduledStartTime != null && !scheduledStartTime.isEmpty();

        var manager = event.getManager();
        if (name != null && !name.isEmpty()) manager.setName(name);
        if (description != null && !description.isEmpty()) manager.setDescription(description);
        if (movingStart) manager.setStartTime(parseTime(scheduledStartTime));
        if (location != null && !location.isEmpty()) manager.setLocation(location);
        if (status != null && !status.isEmpty()) {
            manager.setStatus(switch (Integer.parseInt(status)) {
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

        if (recurrenceRule != null && !recurrenceRule.isEmpty()) {
            String anchor = movingStart ? scheduledStartTime : raw.getString("scheduled_start_time", null);
            DataObject rule = RecurrenceRule.parse(recurrenceRule, anchor);
            patchRaw(guild.getId(), event.getId(), DataObject.empty().put("recurrence_rule", rule));
            result.append("\n  • Recurrence set: ").append(RecurrenceRule.describe(rule));
        } else if (existingRecurrence != null && movingStart) {
            // The bug this tool used to have. A recurring event's series is anchored by
            // recurrence_rule.start, not by scheduled_start_time. Moving only the latter shifts the
            // next occurrence and then the series snaps back to its old time, while the tool
            // reported plain success. Move the anchor with it and say so.
            DataObject moved = existingRecurrence.put("start", scheduledStartTime);
            patchRaw(guild.getId(), event.getId(), DataObject.empty().put("recurrence_rule", moved));
            result.append("\n  • This is a recurring event, so its recurrence anchor was moved to ")
                    .append(scheduledStartTime)
                    .append(" as well. Without that the series would have snapped back to the old time.")
                    .append("\n  • Recurrence is now: ").append(RecurrenceRule.describe(moved));
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
        } catch (RuntimeException e) {
            // Recurrence detail is an enhancement to this listing, not its purpose. Losing it
            // should not turn a working list call into a failure.
            rules.clear();
        }

        return "Retrieved " + events.size() + " scheduled events:\n" +
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
