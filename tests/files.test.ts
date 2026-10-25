import { afterAll, describe, expect, test } from "bun:test";
import { mkdir, mkdtemp, rm, symlink, writeFile } from "node:fs/promises";
import { tmpdir } from "node:os";
import { join } from "node:path";
import type { Context } from "../src/context.ts";
import {
  createFileLoader,
  type DownloadRequest,
  isNonPublicAddress,
} from "../src/files.ts";
import { ToolError } from "../src/tool.ts";
import { loadFiles } from "../src/tools/format.ts";

const dir = await mkdtemp(join(tmpdir(), "discord-mcp-files-"));
const root = join(dir, "root");
await mkdir(root);
await writeFile(join(root, "ok.txt"), "hello");
await writeFile(join(dir, "secret.txt"), "secret");
await symlink(join(dir, "secret.txt"), join(root, "link.txt"));
afterAll(() => rm(dir, { recursive: true, force: true }));

const fetchCalls: string[] = [];
const fakeRequest: DownloadRequest = async (url) => {
  fetchCalls.push(String(url));
  return new Response("image-bytes");
};

const loader = (
  overrides: Partial<Parameters<typeof createFileLoader>[0]> = {},
) =>
  createFileLoader({
    roots: [root],
    maxBytes: 100,
    resolveHost: async (host) =>
      host === "internal.example" ? ["10.0.0.5"] : ["93.184.216.34"],
    request: fakeRequest,
    ...overrides,
  });

describe("isNonPublicAddress", () => {
  test.each([
    "127.0.0.1",
    "10.1.2.3",
    "172.16.0.1",
    "192.168.1.1",
    "169.254.169.254",
    "100.64.0.1",
    "0.0.0.0",
    "224.0.0.1",
    "::1",
    "::",
    "fd00::1",
    "fe80::1",
    "::ffff:127.0.0.1",
    "::ffff:7f00:1",
    "::ffff:a9fe:a9fe",
    "::7f00:1",
    "2002:7f00:1::",
    "64:ff9b::7f00:1",
    "fec0::1",
    "2001:db8::1",
    "198.51.100.7",
    "not-an-ip",
  ])("%s is not public", (address) => {
    expect(isNonPublicAddress(address)).toBe(true);
  });

  test.each(["93.184.216.34", "1.1.1.1", "2606:4700::1111"])(
    "%s is public",
    (address) => {
      expect(isNonPublicAddress(address)).toBe(false);
    },
  );
});

describe("createFileLoader", () => {
  test("reads a file under a root", async () => {
    const file = await loader().load({ filePath: join(root, "ok.txt") });
    expect(file.name).toBe("ok.txt");
    expect(file.contents.toString()).toBe("hello");
  });

  test("rejects a path outside the roots, also through a symlink or ..", async () => {
    await expect(
      loader().load({ filePath: join(dir, "secret.txt") }),
    ).rejects.toThrow(ToolError);
    await expect(
      loader().load({ filePath: join(root, "link.txt") }),
    ).rejects.toThrow(ToolError);
    await expect(
      loader().load({ filePath: join(root, "..", "secret.txt") }),
    ).rejects.toThrow(ToolError);
  });

  test("rejects a path under a root that is not a regular file", async () => {
    await expect(loader().load({ filePath: root })).rejects.toThrow(ToolError);
  });

  test("rejects every local read when no root is set", async () => {
    await expect(
      loader({ roots: [] }).load({ filePath: join(root, "ok.txt") }),
    ).rejects.toThrow(ToolError);
  });

  test("rejects a file over the size limit", async () => {
    await expect(
      loader({ maxBytes: 2 }).load({ filePath: join(root, "ok.txt") }),
    ).rejects.toThrow(ToolError);
  });

  test("downloads a public https URL and takes the name from the path", async () => {
    const file = await loader().load({
      fileUrl: "https://cdn.example/a/pic.png",
    });
    expect(file.name).toBe("pic.png");
    expect(file.contents.toString()).toBe("image-bytes");
  });

  test("refuses http, private hosts, and IP literals before any request", async () => {
    fetchCalls.length = 0;
    for (const fileUrl of [
      "http://cdn.example/pic.png",
      "https://internal.example/pic.png",
      "https://169.254.169.254/latest/meta-data",
      "https://[::1]/x",
      "https://[::ffff:127.0.0.1]/x",
      "https://[::ffff:169.254.169.254]/latest/meta-data",
      "https://2130706433/x",
    ]) {
      await expect(loader().load({ fileUrl })).rejects.toThrow(ToolError);
    }
    expect(fetchCalls).toEqual([]);
  });

  test("refuses a redirect", async () => {
    const redirecting = (async () =>
      new Response(null, {
        status: 302,
        headers: { location: "https://169.254.169.254/" },
      })) as DownloadRequest;
    await expect(
      loader({ request: redirecting }).load({
        fileUrl: "https://cdn.example/x",
      }),
    ).rejects.toThrow(ToolError);
  });

  test("stops a download over the size limit", async () => {
    const big = (async () => new Response("x".repeat(500))) as DownloadRequest;
    await expect(
      loader({ request: big }).load({ fileUrl: "https://cdn.example/x" }),
    ).rejects.toThrow(ToolError);
  });

  test("decodes base64 and data URIs and needs a file name", async () => {
    const raw = await loader().load({
      fileData: btoa("hi"),
      fileName: "a.txt",
    });
    expect(raw.contents.toString()).toBe("hi");
    const uri = await loader().load({
      fileData: `data:text/plain;base64,${btoa("yo")}`,
      fileName: "b.txt",
    });
    expect(uri.contents.toString()).toBe("yo");
    await expect(loader().load({ fileData: btoa("hi") })).rejects.toThrow(
      ToolError,
    );
  });

  test("needs exactly one source and lets fileName override the name", async () => {
    await expect(loader().load({})).rejects.toThrow(ToolError);
    await expect(
      loader().load({
        filePath: join(root, "ok.txt"),
        fileData: btoa("x"),
        fileName: "x",
      }),
    ).rejects.toThrow(ToolError);
    const renamed = await loader().load({
      filePath: join(root, "ok.txt"),
      fileName: "renamed.txt",
    });
    expect(renamed.name).toBe("renamed.txt");
  });
});

describe("download through the real HTTPS client", () => {
  test("refuses a host whose DNS answer turns private after the check, without connecting", async () => {
    const answers = [["93.184.216.34"], ["127.0.0.1"]];
    let lookups = 0;
    const rebinding = createFileLoader({
      roots: [],
      maxBytes: 100,
      resolveHost: async () => answers[Math.min(lookups++, 1)] ?? [],
    });
    const error = await rebinding
      .load({ fileUrl: "https://rebind.example/x" })
      .catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ToolError);
    expect(lookups).toBe(2);
  });
});

describe("byte limits", () => {
  test("load takes a smaller limit than the default", async () => {
    await expect(
      loader().load({ fileData: btoa("12345"), fileName: "a" }, 3),
    ).rejects.toThrow(ToolError);
  });

  test("the files of one message share one budget", async () => {
    const ctx = {
      config: { maxUploadBytes: 10 },
      files: createFileLoader({ roots: [], maxBytes: 10 }),
    } as unknown as Context;
    const file = (text: string) => ({ fileData: btoa(text), fileName: "f" });
    const fits = await loadFiles(ctx, [file("1234"), file("5678")]);
    expect(fits.map((f) => f.contents.length)).toEqual([4, 4]);
    await expect(
      loadFiles(ctx, [file("123456"), file("789012")]),
    ).rejects.toThrow(ToolError);
  });
});
