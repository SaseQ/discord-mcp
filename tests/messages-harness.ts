/** A message as Oceanic returns it, with the fields that the message tools read. */
export const fakeMessage = (overrides: Record<string, unknown> = {}) => ({
  id: "200000000000000001",
  channelID: "300000000000000001",
  guildID: "111111111111111111",
  type: 0,
  content: "hello",
  timestamp: new Date("2026-01-02T03:04:05.000Z"),
  editedTimestamp: null,
  author: {
    id: "400000000000000001",
    username: "alex",
    globalName: "Alex",
    bot: false,
  },
  attachments: { toArray: () => [] },
  embeds: [],
  messageReference: null,
  ...overrides,
});

/** An attachment as Oceanic returns it. */
export const fakeAttachment = (overrides: Record<string, unknown> = {}) => ({
  id: "500000000000000001",
  filename: "notes.txt",
  size: 12,
  contentType: "text/plain",
  url: "https://cdn.discordapp.com/attachments/notes.txt",
  proxyURL: "https://media.discordapp.net/attachments/notes.txt",
  ...overrides,
});
