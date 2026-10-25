import { describe, expect, test } from "bun:test";
import { createFileLoader, type DownloadRequest } from "../src/files.ts";
import { emojiTools } from "../src/tools/emojis.ts";
import { callTool, fakeContext, recorder } from "./harness.ts";

const EMOJI = "400000000000000004";
const MAX_BYTES = 10 * 1024 * 1024;

/** A file loader that uses the real SSRF checks, with the DNS lookup and the fetch replaced by fakes. */
const loaderResolving = (
  address: string,
  fetch = recorder(new Response("never")),
) =>
  createFileLoader({
    roots: [],
    maxBytes: MAX_BYTES,
    resolveHost: async () => [address],
    request: fetch as unknown as DownloadRequest,
  });

/** Bytes that start with the magic number of an image type, followed by padding. */
const imageBytes = (magic: number[], size = 64) => {
  const bytes = new Uint8Array(size);
  bytes.set(magic);
  return bytes;
};

const PNG = [0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a];
const JPEG = [0xff, 0xd8, 0xff, 0xe0];
const GIF = [0x47, 0x49, 0x46, 0x38, 0x39, 0x61];
const WEBP = [0x52, 0x49, 0x46, 0x46, 0, 0, 0, 0, 0x57, 0x45, 0x42, 0x50];

const base64 = (bytes: Uint8Array) => Buffer.from(bytes).toString("base64");

describe("create_emoji image sources", () => {
  test("an image URL that resolves to the cloud metadata address never reaches Discord or the network", async () => {
    const fetch = recorder(new Response("never"));
    const createEmoji = recorder(undefined);
    const ctx = fakeContext(
      { guilds: { createEmoji } },
      { files: loaderResolving("169.254.169.254", fetch) },
    );

    const result = await callTool(emojiTools, ctx, "create_emoji", {
      name: "meta",
      imageUrl: "https://metadata.example.test/latest",
    });

    expect(result.isError).toBe(true);
    expect(createEmoji.calls).toEqual([]);
    expect(fetch.calls).toEqual([]);
  });

  test("a base64 image over 256 KiB is refused before createEmoji", async () => {
    const createEmoji = recorder(undefined);
    const ctx = fakeContext(
      { guilds: { createEmoji } },
      { files: loaderResolving("93.184.216.34") },
    );
    const oversize = imageBytes(PNG, 256 * 1024 + 1);

    const result = await callTool(emojiTools, ctx, "create_emoji", {
      name: "huge",
      image: base64(oversize),
    });

    expect(result.isError).toBe(true);
    expect(createEmoji.calls).toEqual([]);
  });

  test.each([
    ["PNG", PNG, "image/png"],
    ["JPEG", JPEG, "image/jpeg"],
    ["GIF", GIF, "image/gif"],
    ["WebP", WEBP, "image/webp"],
  ])(
    "a %s image is sent with its MIME type from the magic bytes",
    async (_, magic, mime) => {
      const createEmoji = recorder({ id: EMOJI, name: "pic", animated: false });
      const ctx = fakeContext(
        { guilds: { createEmoji } },
        { files: loaderResolving("93.184.216.34") },
      );

      const result = await callTool(emojiTools, ctx, "create_emoji", {
        name: "pic",
        image: base64(imageBytes(magic)),
      });

      expect(result.isError).toBe(false);
      const options = createEmoji.calls[0]?.[1] as { image: string };
      expect(options.image.startsWith(`data:${mime};base64,`)).toBe(true);
    },
  );

  test("bytes that are not a supported image are refused before createEmoji", async () => {
    const createEmoji = recorder(undefined);
    const ctx = fakeContext(
      { guilds: { createEmoji } },
      { files: loaderResolving("93.184.216.34") },
    );

    const result = await callTool(emojiTools, ctx, "create_emoji", {
      name: "text",
      image: base64(new TextEncoder().encode("hello world, not an image")),
    });

    expect(result.isError).toBe(true);
    expect(createEmoji.calls).toEqual([]);
  });
});
