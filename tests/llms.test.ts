import { expect, test } from "bun:test";
import { LLMS_FULL, renderLlmsFull } from "../scripts/llms.ts";

test("llms-full.txt matches the README and the tool definitions", async () => {
  expect(await Bun.file(LLMS_FULL).text()).toBe(await renderLlmsFull());
});
