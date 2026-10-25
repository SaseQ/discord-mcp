import { describe, expect, test } from "bun:test";
import { eventTools } from "../src/tools/events.ts";
import { callTool, fakeContext, recorder } from "./harness.ts";

const CHANNEL = "300000000000000003";
const EVENT = "500000000000000005";
const FUTURE_START = "2100-01-01T18:00:00Z";
const FUTURE_END = "2100-01-01T20:00:00Z";

/** A channel of the server, shaped like the Oceanic channel that getChannels returns. */
const fakeChannel = { id: CHANNEL, name: "stage", type: 13 };

/** An event shaped like the Oceanic scheduled event that the REST routes return. */
const fakeEvent = (fields: Record<string, unknown> = {}) => ({
  id: EVENT,
  name: "Town hall",
  description: null,
  entityType: 3,
  entityID: null,
  channelID: null,
  entityMetadata: { location: "Lobby" },
  privacyLevel: 2,
  scheduledStartTime: new Date(FUTURE_START),
  scheduledEndTime: new Date(FUTURE_END),
  status: 1,
  userCount: 7,
  ...fields,
});

describe("scheduled event time validation", () => {
  test.each([
    [
      "a start time with no offset",
      { scheduledStartTime: "2100-01-01T18:00:00" },
    ],
    ["an end time with no offset", { scheduledEndTime: "2100-01-01T20:00:00" }],
  ])("create refuses %s before any REST call", async (_, times) => {
    const createScheduledEvent = recorder(fakeEvent());
    const ctx = fakeContext({
      guilds: { createScheduledEvent, getChannels: recorder([fakeChannel]) },
    });

    const result = await callTool(
      eventTools,
      ctx,
      "create_guild_scheduled_event",
      {
        name: "Town hall",
        entityType: 3,
        location: "Lobby",
        scheduledStartTime: FUTURE_START,
        scheduledEndTime: FUTURE_END,
        ...times,
      },
    );

    expect(result.isError).toBe(true);
    expect(createScheduledEvent.calls).toEqual([]);
  });

  test("create refuses an end time that is not after the start time", async () => {
    const createScheduledEvent = recorder(fakeEvent());
    const ctx = fakeContext({ guilds: { createScheduledEvent } });

    const result = await callTool(
      eventTools,
      ctx,
      "create_guild_scheduled_event",
      {
        name: "Town hall",
        entityType: 3,
        location: "Lobby",
        scheduledStartTime: FUTURE_END,
        scheduledEndTime: FUTURE_START,
      },
    );

    expect(result.isError).toBe(true);
    expect(createScheduledEvent.calls).toEqual([]);
  });

  test("create refuses a start time in the past", async () => {
    const createScheduledEvent = recorder(fakeEvent());
    const ctx = fakeContext({ guilds: { createScheduledEvent } });

    const result = await callTool(
      eventTools,
      ctx,
      "create_guild_scheduled_event",
      {
        name: "Town hall",
        entityType: 3,
        location: "Lobby",
        scheduledStartTime: "2000-01-01T18:00:00Z",
        scheduledEndTime: "2000-01-01T20:00:00Z",
      },
    );

    expect(result.isError).toBe(true);
    expect(createScheduledEvent.calls).toEqual([]);
  });

  test("create refuses an external event without a location", async () => {
    const createScheduledEvent = recorder(fakeEvent());
    const ctx = fakeContext({ guilds: { createScheduledEvent } });

    const result = await callTool(
      eventTools,
      ctx,
      "create_guild_scheduled_event",
      {
        name: "Town hall",
        entityType: 3,
        scheduledStartTime: FUTURE_START,
        scheduledEndTime: FUTURE_END,
      },
    );

    expect(result.isError).toBe(true);
    expect(createScheduledEvent.calls).toEqual([]);
  });

  test("create sends an external event with its location and its offset times", async () => {
    const createScheduledEvent = recorder(fakeEvent());
    const ctx = fakeContext({ guilds: { createScheduledEvent } });

    const result = await callTool(
      eventTools,
      ctx,
      "create_guild_scheduled_event",
      {
        name: "Town hall",
        entityType: 3,
        location: "Lobby",
        scheduledStartTime: "2100-01-01T19:00:00+01:00",
        scheduledEndTime: FUTURE_END,
      },
    );

    expect(result.isError).toBe(false);
    const options = createScheduledEvent.calls[0]?.[1] as Record<
      string,
      unknown
    >;
    expect(options).toMatchObject({
      entityType: 3,
      entityMetadata: { location: "Lobby" },
      scheduledStartTime: "2100-01-01T18:00:00.000Z",
      scheduledEndTime: FUTURE_END.replace("Z", ".000Z"),
    });
  });
});

describe("list_guild_scheduled_events", () => {
  test("maps the fields of each event and asks for the user count", async () => {
    const getScheduledEvents = recorder([fakeEvent()]);
    const ctx = fakeContext({ guilds: { getScheduledEvents } });

    const result = await callTool(
      eventTools,
      ctx,
      "list_guild_scheduled_events",
      {},
    );

    expect(result.isError).toBe(false);
    expect(getScheduledEvents.calls[0]?.[1]).toBe(1);
    expect(result.data).toEqual({
      events: [
        {
          id: EVENT,
          name: "Town hall",
          entityType: 3,
          status: 1,
          privacyLevel: 2,
          location: "Lobby",
          startTime: "2100-01-01T18:00:00.000Z",
          endTime: "2100-01-01T20:00:00.000Z",
          userCount: 7,
        },
      ],
    });
  });
});
