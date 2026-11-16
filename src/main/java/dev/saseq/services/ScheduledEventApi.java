package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.api.utils.data.DataArray;
import net.dv8tion.jda.internal.requests.RestActionImpl;

class ScheduledEventApi {
    private final JDA jda;
    ScheduledEventApi(JDA jda) { this.jda = jda; }

    DataObject get(String guildId, String eventId) {
        var route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(id(guildId), id(eventId));
        DataObject result = new RestActionImpl<DataObject>(jda, route, (response, request) -> {
            try { return response.getObject(); }
            catch (RuntimeException e) { return null; }
        }).complete();
        if (result == null) throw new IllegalStateException("Event response unavailable");
        return result;
    }

    DataArray list(String guildId, boolean withUserCount) {
        var route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events").compile(id(guildId))
                .withQueryParams("with_user_count", Boolean.toString(withUserCount));
        DataArray result = new RestActionImpl<DataArray>(jda, route, (response, request) -> {
            try { return response.getArray(); }
            catch (RuntimeException e) { return null; }
        }).complete();
        if (result == null) throw new IllegalStateException("Event list response unavailable");
        return result;
    }

    DataObject create(String guildId, DataObject body) {
        var route = Route.custom(Method.POST, "guilds/{guild_id}/scheduled-events").compile(id(guildId));
        return write(route, body);
    }

    DataObject edit(String guildId, String eventId, DataObject body) {
        var route = Route.custom(Method.PATCH, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(id(guildId), id(eventId));
        return write(route, body);
    }

    private DataObject write(Route.CompiledRoute route, DataObject body) {
        try {
            return new RestActionImpl<DataObject>(jda, route, body, (response, request) -> {
                try { return response.getObject(); }
                catch (RuntimeException e) { return DataObject.empty(); }
            }).complete();
        } catch (RuntimeException e) {
            for (Throwable cause = e; cause != null; cause = cause.getCause()) {
                if (cause instanceof java.io.IOException) {
                    throw new IllegalStateException("Discord write outcome is unknown; inspect the event before retrying", e);
                }
            }
            throw e;
        }
    }

    static String id(String value) {
        if (value == null || !value.matches("[0-9]+")) {
            throw new IllegalArgumentException("Invalid Discord ID");
        }
        try { Long.parseUnsignedLong(value); }
        catch (NumberFormatException e) { throw new IllegalArgumentException("Invalid Discord ID"); }
        return value;
    }
}
