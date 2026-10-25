import { realpath, stat } from "node:fs/promises";
import { get as httpsGet } from "node:https";
import type { LookupFunction } from "node:net";
import { BlockList, isIP } from "node:net";
import { basename, resolve, sep } from "node:path";
import { dns } from "bun";
import { ToolError } from "./tool.ts";

export interface LoadedFile {
  name: string;
  contents: Buffer;
}

export interface FileSource {
  /** An absolute path under one of `DISCORD_MCP_FILE_ROOTS`. */
  filePath?: string | undefined;
  /** A public `https` URL. */
  fileUrl?: string | undefined;
  /** Base64 data, raw or as a data URI. */
  fileData?: string | undefined;
  fileName?: string | undefined;
}

export interface FileLoaderOptions {
  roots: readonly string[];
  maxBytes: number;
  /** Resolves a host name to its addresses. Tests replace it. */
  resolveHost?: (host: string) => Promise<string[]>;
  /** Sends the GET request through the given DNS lookup. Tests replace it. */
  request?: DownloadRequest;
}

/** The part of an HTTP response that a download reads. */
export interface DownloadResponse {
  status: number;
  headers: Headers;
  body: AsyncIterable<Uint8Array> | null;
}

export type DownloadRequest = (
  url: URL,
  options: { lookup: LookupFunction; timeoutMs: number },
) => Promise<DownloadResponse>;

const FETCH_TIMEOUT_MS = 15_000;

export const createFileLoader = (options: FileLoaderOptions) => {
  const resolveHost =
    options.resolveHost ??
    (async (host: string) => (await dns.lookup(host)).map((r) => r.address));
  const request = options.request ?? requestWithNode;

  /**
   * Resolves the host again at connect time and refuses a non-public address there, so the address that is checked is the address that is used.
   * Without it, a DNS server could answer with a public address for the check and a private one for the request.
   */
  const lookup: LookupFunction = (hostname, lookupOptions, callback) => {
    resolveHost(hostname).then(
      (addresses) => {
        const blocked = addresses.find(isNonPublicAddress);
        if (addresses.length === 0 || blocked !== undefined) {
          callback(
            new ToolError(
              `URL resolves to a non-public address (${blocked ?? "none"})`,
            ) as NodeJS.ErrnoException,
            "",
            4,
          );
          return;
        }
        const all = addresses.map((address) => ({
          address,
          family: isIP(address),
        }));
        if (lookupOptions.all) {
          (callback as unknown as (e: null, a: typeof all) => void)(null, all);
        } else {
          const [first] = all;
          callback(null, first?.address ?? "", first?.family ?? 4);
        }
      },
      (error: Error) => callback(error as NodeJS.ErrnoException, "", 4),
    );
  };

  const readLocal = async (
    path: string,
    maxBytes: number,
  ): Promise<LoadedFile> => {
    if (options.roots.length === 0) {
      throw new ToolError(
        "Reading local files is turned off. Set DISCORD_MCP_FILE_ROOTS to the folders that tools may read.",
      );
    }
    const underRoot = (candidate: string, roots: readonly string[]) =>
      roots.some(
        (root) => candidate === root || candidate.startsWith(root + sep),
      );
    // The path check runs before any file system access, so an error does not tell whether a path outside the roots exists.
    if (!underRoot(resolve(path), options.roots)) {
      throw new ToolError(`File is outside DISCORD_MCP_FILE_ROOTS: ${path}`);
    }
    let real: string;
    try {
      real = await realpath(path);
    } catch {
      throw new ToolError(`File not found: ${path}`);
    }
    const realRoots = await Promise.all(
      options.roots.map((root) => realpath(root).catch(() => root)),
    );
    if (!underRoot(real, realRoots)) {
      throw new ToolError(`File is outside DISCORD_MCP_FILE_ROOTS: ${path}`);
    }
    if (!(await stat(real)).isFile()) {
      throw new ToolError(`Not a regular file: ${path}`);
    }
    const file = Bun.file(real);
    if (file.size > maxBytes) {
      throw new ToolError(
        `File is ${file.size} bytes, over the limit of ${maxBytes}`,
      );
    }
    return {
      name: basename(real),
      contents: Buffer.from(await file.arrayBuffer()),
    };
  };

  const download = async (
    raw: string,
    maxBytes = options.maxBytes,
  ): Promise<LoadedFile> => {
    let url: URL;
    try {
      url = new URL(raw);
    } catch {
      throw new ToolError(`Invalid URL: ${raw}`);
    }
    if (url.protocol !== "https:")
      throw new ToolError("Only https URLs are allowed");
    const host = url.hostname.replace(/^\[|\]$/g, "");
    const addresses = isIP(host) ? [host] : await resolveHost(host);
    if (addresses.length === 0)
      throw new ToolError(`Host does not resolve: ${host}`);
    const blocked = addresses.find(isNonPublicAddress);
    if (blocked !== undefined) {
      throw new ToolError(`URL resolves to a non-public address (${blocked})`);
    }

    let response: DownloadResponse;
    try {
      response = await request(url, { lookup, timeoutMs: FETCH_TIMEOUT_MS });
    } catch (error) {
      if (error instanceof ToolError) throw error;
      throw new ToolError(
        `Download failed: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
    if (response.status >= 300 && response.status < 400) {
      throw new ToolError("The URL redirects; give the final URL");
    }
    if (response.status < 200 || response.status >= 300)
      throw new ToolError(`Download failed with HTTP ${response.status}`);
    const contents = await readCapped(response, maxBytes);
    const name = basename(url.pathname) || "file";
    return { name, contents };
  };

  /** Loads one file of at most `maxBytes`, which defaults to `DISCORD_MCP_MAX_UPLOAD_BYTES`. */
  const load = async (
    source: FileSource,
    maxBytes = options.maxBytes,
  ): Promise<LoadedFile> => {
    const given = [source.filePath, source.fileUrl, source.fileData].filter(
      (v) => v !== undefined && v !== "",
    );
    if (given.length !== 1) {
      throw new ToolError("Give exactly one of filePath, fileUrl, or fileData");
    }
    let file: LoadedFile;
    if (source.filePath) {
      file = await readLocal(source.filePath, maxBytes);
    } else if (source.fileUrl) {
      file = await download(source.fileUrl, maxBytes);
    } else {
      if (!source.fileName)
        throw new ToolError("fileName is required with fileData");
      file = {
        name: source.fileName,
        contents: decodeBase64(source.fileData ?? "", maxBytes),
      };
    }
    return source.fileName ? { ...file, name: source.fileName } : file;
  };

  return { load, download };
};

export type FileLoader = ReturnType<typeof createFileLoader>;

const decodeBase64 = (data: string, maxBytes: number): Buffer => {
  const payload = data.startsWith("data:")
    ? data.slice(data.indexOf(",") + 1)
    : data;
  const contents = Buffer.from(payload, "base64");
  if (contents.length === 0)
    throw new ToolError("fileData is empty or not base64");
  if (contents.length > maxBytes) {
    throw new ToolError(
      `File is ${contents.length} bytes, over the limit of ${maxBytes}`,
    );
  }
  return contents;
};

/** Sends the GET request with `node:https`, because Bun's `fetch` takes no DNS lookup of its own. It follows no redirect. */
const requestWithNode: DownloadRequest = (url, { lookup, timeoutMs }) =>
  new Promise((resolve, reject) => {
    const req = httpsGet(
      url,
      { lookup, headers: { "user-agent": "discord-mcp" } },
      (res) => {
        const headers = new Headers();
        for (const [key, value] of Object.entries(res.headers)) {
          if (typeof value === "string") headers.set(key, value);
        }
        resolve({ status: res.statusCode ?? 0, headers, body: res });
      },
    );
    req.setTimeout(timeoutMs, () =>
      req.destroy(new ToolError(`Download timed out after ${timeoutMs} ms`)),
    );
    req.on("error", reject);
  });

const readCapped = async (
  response: DownloadResponse,
  maxBytes: number,
): Promise<Buffer> => {
  const declared = Number(response.headers.get("content-length") ?? "0");
  if (declared > maxBytes) {
    throw new ToolError(
      `File is ${declared} bytes, over the limit of ${maxBytes}`,
    );
  }
  const chunks: Uint8Array[] = [];
  let total = 0;
  if (response.body === null) return Buffer.alloc(0);
  for await (const chunk of response.body) {
    total += chunk.byteLength;
    if (total > maxBytes)
      throw new ToolError(`File is over the limit of ${maxBytes} bytes`);
    chunks.push(chunk);
  }
  return Buffer.concat(chunks);
};

const NON_PUBLIC_IPV4 = new BlockList();
for (const [network, prefix] of [
  ["0.0.0.0", 8],
  ["10.0.0.0", 8],
  ["100.64.0.0", 10],
  ["127.0.0.0", 8],
  ["169.254.0.0", 16],
  ["172.16.0.0", 12],
  ["192.0.0.0", 24],
  ["192.0.2.0", 24],
  ["192.168.0.0", 16],
  ["198.18.0.0", 15],
  ["198.51.100.0", 24],
  ["203.0.113.0", 24],
  ["224.0.0.0", 3],
] as const) {
  NON_PUBLIC_IPV4.addSubnet(network, prefix, "ipv4");
}

/** IPv6 is public only inside global unicast `2000::/3`, which leaves out every IPv4-mapped, IPv4-compatible, local, and multicast form. */
const GLOBAL_UNICAST_IPV6 = new BlockList();
GLOBAL_UNICAST_IPV6.addSubnet("2000::", 3, "ipv6");

const RESERVED_IPV6 = new BlockList();
for (const [network, prefix] of [
  ["2001::", 23],
  ["2001:db8::", 32],
  ["2002::", 16],
  ["3fff::", 20],
] as const) {
  RESERVED_IPV6.addSubnet(network, prefix, "ipv6");
}

/** True for every address that is not a public unicast address, and for anything that is not an IP address. */
export const isNonPublicAddress = (address: string): boolean => {
  switch (isIP(address)) {
    case 4:
      return NON_PUBLIC_IPV4.check(address, "ipv4");
    case 6:
      return (
        !GLOBAL_UNICAST_IPV6.check(address, "ipv6") ||
        RESERVED_IPV6.check(address, "ipv6")
      );
    default:
      return true;
  }
};
