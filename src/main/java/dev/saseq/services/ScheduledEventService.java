package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Guild;
import net.dv8tion.jda.api.entities.Icon;
import net.dv8tion.jda.api.entities.Member;
import net.dv8tion.jda.api.entities.ScheduledEvent;
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel;
import net.dv8tion.jda.api.exceptions.ParsingException;
import net.dv8tion.jda.api.requests.restaction.ScheduledEventAction;
import net.dv8tion.jda.api.utils.data.DataObject;
import okhttp3.HttpUrl;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Service
public class ScheduledEventService {

    private static final int MAX_COVER_BYTES = 5 * 1024 * 1024;
    private static final byte[] PNG_SIGNATURE = {(byte)137, 80, 78, 71, 13, 10, 26, 10};

    private final JDA jda;
    private final ScheduledEventApi api;

    @Value("${DISCORD_GUILD_ID:}")
    private String defaultGuildId;

    @Autowired
    public ScheduledEventService(JDA jda) {
        this(jda, new ScheduledEventApi(jda));
    }

    ScheduledEventService(JDA jda, ScheduledEventApi api) {
        this.jda = jda;
        this.api = api;
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

    private static boolean provided(String value) {
        return value != null && !value.isEmpty();
    }

    private OffsetDateTime resolveEndTime(DataObject current, String start, String end) {
        try {
            OffsetDateTime oldStart = parseTime(current.getString("scheduled_start_time"));
            OffsetDateTime effectiveStart = provided(start) ? parseTime(start) : oldStart;
            if (provided(end)) {
                OffsetDateTime result = parseTime(end);
                if (!result.isAfter(effectiveStart)) throw new IllegalArgumentException("End must be after start");
                return result;
            }
            if (!provided(start)) return null;
            if (!current.hasKey("scheduled_end_time")) throw new IllegalArgumentException("Current end time unavailable");
            if (current.isNull("scheduled_end_time")) return null;
            Duration duration = Duration.between(oldStart, parseTime(current.getString("scheduled_end_time")));
            if (duration.isZero() || duration.isNegative()) throw new IllegalArgumentException("Current duration must be positive");
            return effectiveStart.plus(duration);
        } catch (ParsingException e) {
            throw new IllegalArgumentException("Current event times unavailable", e);
        }
    }

    private String formatSavedEvent(DataObject event) {
        try {
            return "**" + event.getString("name", "unavailable") + "** (ID: " + event.getString("id", "unavailable")
                    + ")\n  • Start: " + event.getString("scheduled_start_time", "unavailable")
                    + "\n  • End: " + (event.hasKey("scheduled_end_time")
                    ? event.getString("scheduled_end_time", "none") : "unavailable")
                    + "\n  • Recurrence: " + recurrenceText(event);
        } catch (RuntimeException e) {
            return "Discord accepted the change; response details unavailable. Check the event list.";
        }
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
            @ToolParam(description = "Recurrence JSON: frequency 0 yearly, 1 monthly, 2 weekly, 3 daily; omitted means one-off", required = false) String recurrenceRule) {

        Guild guild = getGuild(guildId);
        if (name == null || name.isEmpty()) throw new IllegalArgumentException("name cannot be null");
        if (entityType == null || entityType.isEmpty()) throw new IllegalArgumentException("entityType cannot be null");

        int type = Integer.parseInt(entityType);
        OffsetDateTime startTime = parseTime(scheduledStartTime);

        ScheduledEventAction action = prepareCreationAction(guild, name, description, startTime,
                scheduledEndTime, type, channelId, location);

        if (provided(recurrenceRule)) {
            DataObject rule = RecurrenceRule.parse(recurrenceRule, scheduledStartTime);
            if (rule != null) {
                DataObject body = buildRecurringCreation(name, description, startTime, scheduledEndTime,
                        type, channelId, location, rule);
                return "Created scheduled event:\n" + formatSavedEvent(api.create(guild.getId(), body));
            }
        }
        ScheduledEvent event = action.complete();
        return "Created scheduled event:\n" + formatEvent(event);
    }

    private ScheduledEventAction prepareCreationAction(Guild guild, String name, String description,
                                                        OffsetDateTime startTime, String scheduledEndTime,
                                                        int type, String channelId, String location) {
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

        return action;
    }

    private DataObject buildRecurringCreation(String name, String description, OffsetDateTime startTime,
                                               String scheduledEndTime, int type, String channelId,
                                               String location, DataObject rule) {
        DataObject body = DataObject.empty().put("name", name).put("privacy_level", 2)
                .put("entity_type", type).put("scheduled_start_time", startTime.toString())
                .put("recurrence_rule", rule.toMap());
        if (provided(description)) body.put("description", description);
        if (provided(scheduledEndTime)) {
            OffsetDateTime end = parseTime(scheduledEndTime);
            if (!end.isAfter(startTime)) throw new IllegalArgumentException("End must be after start");
            body.put("scheduled_end_time", end.toString());
        }
        if (type == 3) body.put("entity_metadata", Map.of("location", location));
        else body.put("channel_id", channelId);
        return body;
    }

    @Tool(name = "edit_guild_scheduled_event", description = "Modify details of an existing event or change its status (start, complete, cancel)")
    public String editScheduledEvent(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "New status: 1=Scheduled, 2=Active (start), 3=Completed, 4=Canceled", required = false) String status,
            @ToolParam(description = "New name", required = false) String name,
            @ToolParam(description = "New description", required = false) String description,
            @ToolParam(description = "New ISO8601 start time", required = false) String scheduledStartTime,
            @ToolParam(description = "New location (for External events)", required = false) String location,
            @ToolParam(description = "New ISO8601 end time; omit when moving the start to keep the duration", required = false) String scheduledEndTime,
            @ToolParam(description = "Recurrence JSON; omit to keep, or use the string null to clear", required = false) String recurrenceRule) {

        Guild guild = getGuild(guildId);
        ScheduledEvent event = getEvent(guild, eventId);

        OffsetDateTime newStart = provided(scheduledStartTime) ? parseTime(scheduledStartTime) : null;
        Integer newStatus = parseStatusCode(status);
        DataObject current = readCurrentEventForEdit(guild, eventId, newStart, scheduledEndTime, recurrenceRule);
        OffsetDateTime newEnd = newStart != null || provided(scheduledEndTime)
                ? resolveEndTime(current, scheduledStartTime, scheduledEndTime) : null;
        DataObject body = buildRecurrenceUpdate(current, scheduledStartTime, recurrenceRule);
        if (body != null) {
            // Raw writes bypass the manager, so retain its validation and location conversion.
            validateRecurrenceEditText(name, description, location);
            validateRecurrenceEditTimes(current, newStart, newEnd);
            if (provided(name)) body.put("name", name);
            if (provided(description)) body.put("description", description);
            if (provided(location)) {
                applyExternalLocationChange(body, current, location, newStart, newEnd);
            }
            if (newStatus != null) body.put("status", newStatus);
            if (newStart != null) body.put("scheduled_start_time", newStart.toString());
            if (newEnd != null) body.put("scheduled_end_time", newEnd.toString());
            return "Successfully updated scheduled event:\n" + formatSavedEvent(api.edit(guild.getId(), eventId, body));
        }
        saveStandardEdit(event, name, description, location, newStatus, newStart, newEnd);
        return "Successfully updated scheduled event: " + event.getName() + " (ID: " + event.getId() + ")";
    }

    private static Integer parseStatusCode(String status) {
        Integer code = provided(status) ? Integer.parseInt(status) : null;
        if (code != null && (code < 1 || code > 4)) {
            throw new IllegalArgumentException("status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled)");
        }
        return code;
    }

    private DataObject readCurrentEventForEdit(Guild guild, String eventId, OffsetDateTime newStart,
                                               String scheduledEndTime, String recurrenceRule) {
        if (newStart != null || provided(scheduledEndTime) || provided(recurrenceRule)) {
            return api.get(guild.getId(), eventId);
        }
        return null;
    }

    private DataObject buildRecurrenceUpdate(DataObject current, String scheduledStartTime, String recurrenceRule) {
        DataObject rule;
        if (provided(recurrenceRule)) {
            String anchor = recurrenceRule.trim().equals("null") ? null
                    : (provided(scheduledStartTime) ? scheduledStartTime : current.getString("scheduled_start_time"));
            rule = RecurrenceRule.parse(recurrenceRule, anchor);
        } else {
            if (!provided(scheduledStartTime)) return null;
            if (!current.hasKey("recurrence_rule")) throw new IllegalArgumentException("Current recurrence unavailable");
            if (current.isNull("recurrence_rule")) return null;
            try {
                rule = RecurrenceRule.move(current.getObject("recurrence_rule"), scheduledStartTime);
            } catch (RuntimeException e) {
                throw new IllegalArgumentException("Cannot safely move the current recurrence", e);
            }
        }
        return rule == null ? DataObject.empty().putNull("recurrence_rule")
                : DataObject.empty().put("recurrence_rule", rule.toMap());
    }

    private void saveStandardEdit(ScheduledEvent event, String name, String description, String location,
                                  Integer newStatus, OffsetDateTime newStart, OffsetDateTime newEnd) {
        var manager = event.getManager();
        if (newEnd != null) manager.setEndTime(newEnd);
        if (name != null && !name.isEmpty()) manager.setName(name);
        if (description != null && !description.isEmpty()) manager.setDescription(description);
        if (newStart != null) manager.setStartTime(newStart);
        if (location != null && !location.isEmpty()) manager.setLocation(location);
        if (newStatus != null) {
            manager.setStatus(switch (newStatus) {
                case 1 -> ScheduledEvent.Status.SCHEDULED;
                case 2 -> ScheduledEvent.Status.ACTIVE;
                case 3 -> ScheduledEvent.Status.COMPLETED;
                case 4 -> ScheduledEvent.Status.CANCELED;
                default -> throw new IllegalArgumentException("status must be 1 (Scheduled), 2 (Active), 3 (Completed), or 4 (Canceled)");
            });
        }

        manager.complete();
    }

    private static void validateRecurrenceEditText(String name, String description, String location) {
        if (provided(name) && (name.isBlank() || name.length() > 100)) {
            throw new IllegalArgumentException("name must be nonblank and at most 100 characters");
        }
        if (provided(description) && description.length() > 1000) {
            throw new IllegalArgumentException("description must be at most 1000 characters");
        }
        if (provided(location) && (location.isBlank() || location.length() > 100)) {
            throw new IllegalArgumentException("location must be nonblank and at most 100 characters");
        }
    }

    private static void validateRecurrenceEditTimes(DataObject current, OffsetDateTime newStart, OffsetDateTime newEnd) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        if (newStart != null && (current.getInt("status", 0) != 1
                || !newStart.isAfter(now) || !newStart.isBefore(now.plusYears(5)))) {
            throw new IllegalArgumentException("Only scheduled events can move to a future start within five years");
        }
        if (newEnd != null && !newEnd.isBefore(now.plusYears(5))) {
            throw new IllegalArgumentException("End must be within five years");
        }
    }

    private void applyExternalLocationChange(DataObject body, DataObject current, String location,
                                            OffsetDateTime newStart, OffsetDateTime newEnd) {
        int type = current.getInt("entity_type", 0);
        if (type < 1 || type > 3 || (type != 3 && current.getInt("status", 0) != 1)) {
            throw new IllegalArgumentException("Only scheduled events can convert to external events");
        }
        String end = newEnd == null ? current.getString("scheduled_end_time", null) : newEnd.toString();
        if (!provided(end)) throw new IllegalArgumentException("External events require an end time");
        OffsetDateTime convertedEnd = parseTime(end);
        OffsetDateTime convertedStart = newStart == null ? parseTime(current.getString("scheduled_start_time")) : newStart;
        if (!convertedEnd.isAfter(convertedStart)) throw new IllegalArgumentException("End must be after start");
        body.put("entity_metadata", Map.of("location", location)).put("entity_type", 3)
                .putNull("channel_id").put("scheduled_end_time", convertedEnd.toString());
    }

    @Tool(name = "set_guild_scheduled_event_image", description = "Set an event cover from a direct HTTPS PNG/JPEG URL (maximum 5 MiB)")
    public String setScheduledEventImage(
            @ToolParam(description = "Discord server ID", required = false) String guildId,
            @ToolParam(description = "ID of the scheduled event") String eventId,
            @ToolParam(description = "Direct HTTPS PNG or JPEG image URL") String imageUrl) {
        validateCoverUrl(imageUrl);
        Guild guild = getGuild(guildId);
        DataObject current = api.get(guild.getId(), eventId);
        validateCoverTarget(current, eventId);
        Icon icon = downloadCoverIcon(imageUrl);
        DataObject result = api.edit(guild.getId(), eventId, DataObject.empty().put("image", icon.getEncoding()));
        return "Updated scheduled event cover (ID: " + eventId + "). Cover: " + coverUrl(result, eventId);
    }

    private static void validateCoverUrl(String imageUrl) {
        var url = imageUrl == null ? null : HttpUrl.parse(imageUrl);
        if (url == null || !url.isHttps()) throw new IllegalArgumentException("imageUrl must use HTTPS");
    }

    private static void validateCoverTarget(DataObject current, String eventId) {
        if (!eventId.equals(current.getString("id", null))) throw new IllegalArgumentException("Event response unavailable");
        int status = current.getInt("status");
        if (status != 1 && status != 2) throw new IllegalArgumentException("Event is not editable");
    }

    private static Icon downloadCoverIcon(String imageUrl) {
        byte[] bytes = RemoteFetchGuard.fetch(imageUrl, MAX_COVER_BYTES, "image");
        Icon.IconType type;
        if (bytes.length >= PNG_SIGNATURE.length
                && Arrays.equals(Arrays.copyOf(bytes, PNG_SIGNATURE.length), PNG_SIGNATURE)) {
            type = Icon.IconType.PNG;
        } else if (bytes.length >= 3 && (bytes[0] & 255) == 255 && (bytes[1] & 255) == 216 && (bytes[2] & 255) == 255) {
            type = Icon.IconType.JPEG;
        } else throw new IllegalArgumentException("Image must be PNG or JPEG");
        return Icon.from(bytes, type);
    }

    private String coverUrl(DataObject event, String id) {
        if (event == null || !event.hasKey("image")) return "unavailable";
        if (event.isNull("image")) return "none";
        try {
            String hash = event.getString("image");
            if (!hash.matches("[A-Za-z0-9_]+")) return "unavailable";
            return "https://cdn.discordapp.com/guild-events/" + id + "/" + hash + ".png";
        } catch (RuntimeException e) { return "unavailable"; }
    }

    private String recurrenceText(DataObject event) {
        if (event == null || !event.hasKey("recurrence_rule")) return "unavailable";
        if (event.isNull("recurrence_rule")) return "none";
        try { return event.getObject("recurrence_rule").toString(); }
        catch (RuntimeException e) { return "unavailable"; }
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
        var details = new HashMap<String, DataObject>();
        String detailsNote = "";
        try {
            details.putAll(readEventDetails(guild.getId()));
        } catch (RuntimeException e) {
            details.clear();
            detailsNote = "\nRecurrence and cover details unavailable.";
        }
        return "Retrieved " + events.size() + " scheduled events:\n" +
                events.stream()
                        .map(event -> formatListedEvent(event, includeUserCount, details.get(event.getId())))
                        .collect(Collectors.joining("\n")) + detailsNote;
    }

    private Map<String, DataObject> readEventDetails(String guildId) {
        Map<String, DataObject> details = new HashMap<>();
        var raw = api.list(guildId, false);
        for (int i = 0; i < raw.length(); i++) {
            DataObject item = raw.getObject(i);
            details.put(item.getString("id"), item);
        }
        return details;
    }

    private String formatListedEvent(ScheduledEvent event, boolean includeUserCount, DataObject details) {
        StringBuilder sb = new StringBuilder();
        sb.append("- **").append(event.getName()).append("** (ID: ").append(event.getId()).append(")\n");
        sb.append("  • Type: ").append(event.getType()).append(" | Status: ").append(event.getStatus()).append("\n");
        sb.append("  • Start: ").append(event.getStartTime());
        if (event.getEndTime() != null) sb.append(" | End: ").append(event.getEndTime());
        if (includeUserCount) sb.append("\n  • Interested: ").append(event.getInterestedUserCount()).append(" users");
        sb.append("\n  • Recurrence: ").append(recurrenceText(details));
        sb.append("\n  • Cover: ").append(coverUrl(details, event.getId()));
        return sb.toString();
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
