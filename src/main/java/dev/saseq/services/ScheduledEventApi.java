package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.requests.Method;
import net.dv8tion.jda.api.requests.Route;
import net.dv8tion.jda.api.utils.data.DataObject;
import net.dv8tion.jda.internal.requests.RestActionImpl;

class ScheduledEventApi {
    private final JDA jda;
    ScheduledEventApi(JDA jda) { this.jda = jda; }

    DataObject get(String guildId, String eventId) {
        var route = Route.custom(Method.GET, "guilds/{guild_id}/scheduled-events/{event_id}")
                .compile(id(guildId), id(eventId));
        return new RestActionImpl<DataObject>(jda, route, (response, request) -> response.getObject()).complete();
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
