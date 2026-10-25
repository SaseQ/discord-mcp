import { describe, expect, test } from "bun:test";
import {
  isEnabled,
  parseSelectors,
  type ToolTraits,
  unknownSelectors,
} from "../src/policy.ts";

const send: ToolTraits = {
  name: "send_message",
  group: "messages",
  access: "write",
};
const read: ToolTraits = {
  name: "read_messages",
  group: "messages",
  access: "read",
};
const delChannel: ToolTraits = {
  name: "delete_channel",
  group: "channels",
  access: "destructive",
};
const ban: ToolTraits = {
  name: "ban_member",
  group: "moderation",
  access: "destructive",
};
const tools = [send, read, delChannel, ban];

const enabled = (allow: string[], deny: string[] = []) =>
  tools.filter((t) => isEnabled({ allow, deny }, t)).map((t) => t.name);

describe("isEnabled", () => {
  test("star allows every tool", () => {
    expect(enabled(["*"])).toEqual(tools.map((t) => t.name));
  });

  test("deny of an access level removes those tools", () => {
    expect(enabled(["*"], ["@destructive"])).toEqual([
      "send_message",
      "read_messages",
    ]);
  });

  test("allow of an access level keeps only those tools", () => {
    expect(enabled(["@read"])).toEqual(["read_messages"]);
  });

  test("group selectors match the group", () => {
    expect(enabled(["@messages"])).toEqual(["send_message", "read_messages"]);
  });

  test("glob selectors match tool names", () => {
    expect(enabled(["*"], ["delete_*", "*_member"])).toEqual([
      "send_message",
      "read_messages",
    ]);
  });

  test("deny wins over an exact allow", () => {
    expect(enabled(["ban_member"], ["@moderation"])).toEqual([]);
  });

  test("an empty allowlist enables nothing", () => {
    expect(enabled([])).toEqual([]);
  });

  test("glob metacharacters in names are literal", () => {
    expect(enabled(["send.message"])).toEqual([]);
  });
});

describe("parseSelectors", () => {
  test("splits on commas and whitespace and drops blanks", () => {
    expect(parseSelectors(" @read, send_message\n delete_* ,,")).toEqual([
      "@read",
      "send_message",
      "delete_*",
    ]);
  });

  test("returns nothing for an unset value", () => {
    expect(parseSelectors(undefined)).toEqual([]);
  });
});

describe("unknownSelectors", () => {
  test("reports selectors that match no tool", () => {
    expect(
      unknownSelectors(["@read", "delete_chanel", "@nope", "zzz_*"], tools),
    ).toEqual(["delete_chanel", "@nope", "zzz_*"]);
  });
});
