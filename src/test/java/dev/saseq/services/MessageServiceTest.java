package dev.saseq.services;

import net.dv8tion.jda.api.JDA;
import net.dv8tion.jda.api.entities.Message;
import net.dv8tion.jda.api.entities.MessageHistory;
import net.dv8tion.jda.api.entities.User;
import net.dv8tion.jda.api.entities.channel.concrete.TextChannel;
import net.dv8tion.jda.api.requests.RestAction;
import net.dv8tion.jda.api.requests.restaction.MessageCreateAction;
import net.dv8tion.jda.api.utils.FileUpload;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.time.OffsetDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.when;

class MessageServiceTest {

    private static final String CHANNEL_ID = "345678901234567890";
    private static final String MESSAGE_ID = "456789012345678901";

    private JDA jda;
    private MessageService messageService;

    @BeforeEach
    void setUp() {
        jda = mock(JDA.class);
        messageService = new MessageService(jda);
    }

    @Test
    void readMessagesIncludesStableAuthorIdentityFields() {
        TextChannel channel = mock(TextChannel.class);
        MessageHistory history = mock(MessageHistory.class);
        Message guildMessage = message(
                "111111111111111111",
                "123456789012345678",
                "alice",
                "hello"
        );
        Message directAuthorMessage = message(
                "222222222222222222",
                "234567890123456789",
                "bob",
                "hi"
        );
        RestAction<List<Message>> retrievePast = restAction(List.of(guildMessage, directAuthorMessage));

        when(jda.getTextChannelById("345678901234567890")).thenReturn(channel);
        when(channel.getHistory()).thenReturn(history);
        when(history.retrievePast(2)).thenReturn(retrievePast);

        String result = messageService.readMessages("345678901234567890", "2", null, null, null);

        assertThat(result).isEqualTo("**Retrieved 2 messages:** \n"
                + "- (ID: 111111111111111111) **[alice]** (Author ID: 123456789012345678) `2026-05-26T00:00Z`: ```hello```\n"
                + "- (ID: 222222222222222222) **[bob]** (Author ID: 234567890123456789) `2026-05-26T00:00Z`: ```hi```");
    }

    @Test
    void sendFileRefusesLocalPathsWhenNoRootIsConfigured(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("ok.txt"), "hello");
        stubChannel();

        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, file.toString(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_FILE_ROOT");
    }

    @Test
    void sendFileRefusesAFilesystemRootAsTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path file = Files.writeString(dir.resolve("ok.txt"), "hello");
        messageService.fileRoot = dir.getRoot().toString();
        stubChannel();

        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, file.toString(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must not be a filesystem root");
    }

    @Test
    void sendFileRefusesTraversalOutOfTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path secret = Files.writeString(dir.resolve("secret.env"), "DISCORD_TOKEN=hunter2");
        messageService.fileRoot = root.toString();
        stubChannel();

        String traversal = root.resolve("..").resolve(secret.getFileName()).toString();
        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, traversal, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
    }

    @Test
    void sendFileRefusesASymlinkPointingOutOfTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path secret = Files.writeString(dir.resolve("secret.env"), "DISCORD_TOKEN=hunter2");
        Path link = root.resolve("innocent.txt");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation not permitted on this host");
        }
        messageService.fileRoot = root.toString();
        stubChannel();

        // A lexical normalize() would see uploads/innocent.txt and allow this.
        assertThatThrownBy(() -> messageService.sendFile(CHANNEL_ID, link.toString(), null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("outside the allowed upload directory");
    }

    @Test
    void sendFileUploadsAFileInsideTheAllowedRoot(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("uploads"));
        Path file = Files.writeString(root.resolve("poster.png"), "not really a png");
        messageService.fileRoot = root.toString();

        TextChannel channel = stubChannel();
        MessageCreateAction action = mock(MessageCreateAction.class);
        Message sent = mock(Message.class);
        when(channel.sendFiles(any(FileUpload.class))).thenReturn(action);
        when(action.complete()).thenReturn(sent);
        when(sent.getJumpUrl()).thenReturn("https://discord.com/channels/1/2/3");

        String result = messageService.sendFile(CHANNEL_ID, file.toString(), null, null, null, null);

        assertThat(result).contains("File sent successfully");
    }

    @Test
    void downloadAttachmentRefusesWhenNoDownloadRootIsConfigured() {
        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, "999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_DOWNLOAD_ROOT");
    }

    @Test
    void downloadAttachmentDoesNotInheritTheUploadRoot(@TempDir Path dir) throws IOException {
        // Upgrading the jar must not turn an upload directory into a writable one.
        messageService.fileRoot = Files.createDirectory(dir.resolve("uploads")).toString();

        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, "999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_DOWNLOAD_ROOT");
    }

    @Test
    void downloadAttachmentChecksTheRootBeforeTouchingTheNetwork() {
        messageService.downloadRoot = "/does/not/exist";

        // No channel stubbed: reaching Discord at all would throw a different error.
        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, "999", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not exist or cannot be resolved");
    }

    @Test
    void downloadAttachmentRejectsAnOversizedSetBeforeFetchingAnything(@TempDir Path dir) throws IOException {
        messageService.downloadRoot = Files.createDirectory(dir.resolve("downloads")).toString();
        // Three 40 MB files: each is under the per-file cap, together they are over the
        // per-call one. The URLs are unreachable on purpose — if anything is fetched, the
        // failure will not be the one asserted here.
        stubMessageWithAttachments(
                attachment("1", "a.png", 40 * 1024 * 1024),
                attachment("2", "b.png", 40 * 1024 * 1024),
                attachment("3", "c.png", 40 * 1024 * 1024));

        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("over the 100 MB per-call download limit");

        try (var entries = Files.list(Path.of(messageService.downloadRoot))) {
            assertThat(entries).as("nothing written before the limit was enforced").isEmpty();
        }
    }

    @Test
    void downloadAttachmentCollectsEveryFailureAndThrowsWhenNothingSaved(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        messageService.downloadRoot = root.toString();
        // http:// is refused by RemoteFetchGuard on scheme alone, so this drives the collection
        // loop and the savedCount == 0 throw without touching the network.
        stubMessageWithAttachments(
                attachment("1", "a.png", 1024, "http://cdn.example/a.png"),
                attachment("2", "b.png", 1024, "http://cdn.example/b.png"));

        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("No attachments were downloaded")
                // Both are named with their own reason, rather than the first failure aborting
                // the rest — that is the whole point of collecting instead of throwing.
                .hasMessageContaining("(ID 1)")
                .hasMessageContaining("(ID 2)")
                .hasMessageContaining("https scheme");

        try (var entries = Files.list(root)) {
            assertThat(entries).as("no .part files left behind by failed fetches").isEmpty();
        }
    }

    @Test
    void downloadAttachmentRefusesARootItCannotWriteTo(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Assumptions.abort("read-only directories are not enforced the same way here");
        }
        Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("r-xr-xr-x"));
        // root ignores the mode bits, so this asserts nothing when tests run as root — which
        // is exactly what happens in a container-based build.
        if (Files.isWritable(root)) {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
            Assumptions.abort("running as a user that bypasses directory permissions");
        }
        messageService.downloadRoot = root.toString();

        try {
            // Nothing stubbed: if this reached Discord it would fail differently, which is the
            // point — a read-only bind mount must be caught before the CDN transfer, not after.
            assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("not writable by this process");
        } finally {
            Files.setPosixFilePermissions(root, PosixFilePermissions.fromString("rwxr-xr-x"));
        }
    }

    @Test
    void downloadRootRejectsAFilesystemRootAndANonDirectory(@TempDir Path dir) throws IOException {
        // The refactor that split resolveRoot out kept the FILE_ROOT messages intact; these pin
        // that the DOWNLOAD_ROOT variable reports against its own name rather than the old one.
        messageService.downloadRoot = dir.getRoot().toString();
        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_DOWNLOAD_ROOT must not be a filesystem root");

        messageService.downloadRoot = Files.writeString(dir.resolve("a-file"), "x").toString();
        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("DISCORD_MCP_DOWNLOAD_ROOT is not a directory");
    }

    @Test
    void sanitizeFileNameBoundsBytesNotCharactersSoNonAsciiNamesStillFit() {
        // 120 CJK characters is 360 UTF-8 bytes, over ext4's 255-byte NAME_MAX. createTempFile
        // would succeed (its own name is short) and the rename would then fail ENAMETOOLONG on
        // a perfectly ordinary upload — only reachable once non-ASCII names were preserved.
        String longCjk = "写".repeat(200) + ".png";
        String sanitized = MessageService.sanitizeFileName(longCjk);

        assertThat(sanitized.getBytes(StandardCharsets.UTF_8).length).isLessThanOrEqualTo(200);
        // Room left for the caller's "<snowflake>-" prefix inside a 255-byte name.
        assertThat(("123456789012345678-" + sanitized).getBytes(StandardCharsets.UTF_8).length)
                .isLessThan(255);
        // The tail is kept, so the extension survives.
        assertThat(sanitized).endsWith(".png");
        // ASCII names still get the full budget rather than being cut to the byte count of the
        // worst case.
        assertThat(MessageService.sanitizeFileName("x".repeat(300) + ".png")).hasSize(200);
    }

    @Test
    void downloadAttachmentReportsSavedAndFailedTogether(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        messageService.downloadRoot = root.toString();
        stubMessageWithAttachments(
                attachment("1", "good.png", 8, "https://cdn.example/good.png"),
                attachment("2", "bad.png", 8, "https://cdn.example/bad.png"));

        String result;
        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(eq("https://cdn.example/good.png"), anyInt(), any()))
                    .thenReturn("poster!!".getBytes(StandardCharsets.UTF_8));
            guard.when(() -> RemoteFetchGuard.fetch(eq("https://cdn.example/bad.png"), anyInt(), any()))
                    .thenThrow(new IllegalArgumentException("Failed to download attachment from URL"));

            result = messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null);
        }

        // The header counts what landed, not what was asked for.
        assertThat(result).contains("Downloaded 1 of 2 attachment(s) to " + root);
        assertThat(result).contains("1-good.png");
        // The failure section is a contract the model reads and acts on, so pin its wording.
        assertThat(result).contains("Failed, and not saved. Re-running is safe");
        assertThat(result).contains("`bad.png` (ID 2): Failed to download attachment from URL");
        assertThat(Files.readString(root.resolve("1-good.png"))).isEqualTo("poster!!");
        try (var entries = Files.list(root)) {
            assertThat(entries).as("only the good one, and no .part left over").hasSize(1);
        }
    }

    @Test
    void downloadAttachmentStopsFetchingOnceTheCallsBudgetIsSpent(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        messageService.downloadRoot = root.toString();
        // Sizes that pass the preflight, bodies that do not — the lying-metadata case the
        // running total exists for. Three attachments, each claiming 1 KB.
        stubMessageWithAttachments(
                attachment("1", "a.png", 1024, "https://cdn.example/a.png"),
                attachment("2", "b.png", 1024, "https://cdn.example/b.png"),
                attachment("3", "c.png", 1024, "https://cdn.example/c.png"));

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            // Honour the guard's real contract: it never returns more than maxBytes, it throws.
            // The previous version of this test returned 60 MB for a 40 MB allowance, which the
            // real guard cannot do — so it was asserting behaviour production could not reach.
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()))
                    .thenThrow(new RemoteFetchGuard.TooLargeException("attachment exceeds the maximum allowed size"));

            assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);

            // Two fetches, not three: each rejection charges its allowance to the budget, so the
            // 100 MB total is spent after two 50 MB attempts and the third is never requested.
            // Counting only bytes *kept* would leave the total at zero and fetch all three.
            guard.verify(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()), times(2));
        }
    }

    @Test
    void downloadAttachmentChargesRejectedBodiesToTheBudget(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        messageService.downloadRoot = root.toString();
        stubMessageWithAttachments(
                attachment("1", "a.png", 1024, "https://cdn.example/a.png"),
                attachment("2", "b.png", 1024, "https://cdn.example/b.png"),
                attachment("3", "c.png", 1024, "https://cdn.example/c.png"));

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()))
                    .thenThrow(new RemoteFetchGuard.TooLargeException("attachment exceeds the maximum allowed size"));

            assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("No attachments were downloaded")
                    // The per-file limit bound the first two; the message says which limit it was.
                    .hasMessageContaining("the per-file limit")
                    // The third was never attempted, and the reason names the budget, not the file.
                    .hasMessageContaining("1 further attachment(s): not attempted")
                    .hasMessageContaining("100 MB total was already spent");
        }
    }

    @Test
    void aTransferThatDiesPartwayChargesWhatItAlreadyPulled(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        messageService.downloadRoot = root.toString();
        stubMessageWithAttachments(
                attachment("1", "a.png", 1024, "https://cdn.example/a.png"),
                attachment("2", "b.png", 1024, "https://cdn.example/b.png"),
                attachment("3", "c.png", 1024, "https://cdn.example/c.png"));

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            // A reset after 40 MB. The bandwidth is spent even though nothing reached disk, so
            // it has to charge the budget — otherwise ten of these pull ~400 MB while the
            // counter reads zero, which is the gap a size-only rejection check leaves open.
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()))
                    .thenThrow(new RemoteFetchGuard.TransferFailedException(
                            "Failed to download attachment from URL", 40 * 1024 * 1024));

            assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("transfer failed after 40.0 MB");

            // 40 + 40 = 80 leaves 20 MB, so a third is still attempted; 120 would exceed the
            // budget, so there is no fourth. Three attachments, three attempts, budget spent.
            guard.verify(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()), times(3));
        }
    }

    @Test
    void anOrdinaryFetchFailureDoesNotChargeTheBudget(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        messageService.downloadRoot = root.toString();
        stubMessageWithAttachments(
                attachment("1", "a.png", 1024, "https://cdn.example/a.png"),
                attachment("2", "b.png", 1024, "https://cdn.example/b.png"),
                attachment("3", "c.png", 1024, "https://cdn.example/c.png"));

        try (MockedStatic<RemoteFetchGuard> guard = mockStatic(RemoteFetchGuard.class)) {
            // A 404 or an unreachable host spends no meaningful bandwidth, so all three are
            // still attempted. Charging the allowance for these would cut a call short over
            // failures that cost nothing.
            guard.when(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()))
                    .thenThrow(new IllegalArgumentException("Failed to download attachment from URL"));

            assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                    .isInstanceOf(IllegalArgumentException.class);

            guard.verify(() -> RemoteFetchGuard.fetch(any(), anyInt(), any()), times(3));
        }
    }

    @Test
    void downloadAttachmentNamesTheFileThatIsTooBig(@TempDir Path dir) throws IOException {
        messageService.downloadRoot = Files.createDirectory(dir.resolve("downloads")).toString();
        // Inbound attachments are not bound by the send-side upload ceiling.
        stubMessageWithAttachments(attachment("7", "huge-poster.png", 80 * 1024 * 1024));

        assertThatThrownBy(() -> messageService.downloadAttachment(CHANNEL_ID, MESSAGE_ID, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("huge-poster.png")
                .hasMessageContaining("per-file download limit");
    }

    @Test
    void sanitizeFileNameReducesTraversalToASinglePathComponent(@TempDir Path dir) {
        // The name comes from whoever uploaded the file, so these are the inputs that matter.
        // The invariant is not "contains no dots" — a literal ".." inside a name is inert once
        // the separators are gone. It is that the result stays one component directly under the
        // root, which is what actually stops an escape.
        for (String hostile : List.of(
                "../../etc/cron.d/payload",
                "..\\..\\windows\\system32\\evil.dll",
                "C:evil.txt",
                "/etc/shadow",
                "....//....//passwd",
                "poster.png\u0000.sh")) {
            Path resolved = dir.resolve(MessageService.sanitizeFileName(hostile));
            assertThat(resolved.getParent()).as("sanitizing %s", hostile).isEqualTo(dir);
            assertThat(resolved.normalize()).as("normalizing %s", hostile).isEqualTo(resolved);
        }

        assertThat(MessageService.sanitizeFileName("..")).isEqualTo("attachment");
        assertThat(MessageService.sanitizeFileName(".hidden")).isEqualTo("hidden");
        assertThat(MessageService.sanitizeFileName("")).isEqualTo("attachment");
        assertThat(MessageService.sanitizeFileName(null)).isEqualTo("attachment");
        assertThat(MessageService.sanitizeFileName("poster-2026.png")).isEqualTo("poster-2026.png");
        // Non-ASCII names survive as themselves rather than becoming a row of underscores.
        assertThat(MessageService.sanitizeFileName("résumé.pdf")).isEqualTo("résumé.pdf");
        assertThat(MessageService.sanitizeFileName("写真.png")).isEqualTo("写真.png");
        // Windows reserves these whatever the extension, so they must not come through intact.
        assertThat(MessageService.sanitizeFileName("CON.png")).isEqualTo("_CON.png");
        assertThat(MessageService.sanitizeFileName("nul")).isEqualTo("_nul");
        assertThat(MessageService.sanitizeFileName("LPT1.txt")).isEqualTo("_LPT1.txt");
        assertThat(MessageService.sanitizeFileName("console.png")).isEqualTo("console.png");
        // Long names must stay usable, and the extension is the end worth keeping. The budget is
        // in bytes now, so an all-ASCII name gets the whole 200 of it.
        assertThat(MessageService.sanitizeFileName("x".repeat(300) + ".png")).hasSize(200).endsWith(".png");
    }

    @Test
    void writeIntoAllowedRootReplacesASymlinkRatherThanFollowingIt(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        Path secret = Files.writeString(dir.resolve("secret.env"), "DISCORD_TOKEN=hunter2");
        Path link = root.resolve("123-poster.png");
        try {
            Files.createSymbolicLink(link, secret);
        } catch (IOException | UnsupportedOperationException e) {
            Assumptions.abort("symlink creation not permitted on this host");
        }

        Path saved = messageService.writeIntoAllowedRoot(root, "123", "poster.png", "new bytes".getBytes(StandardCharsets.UTF_8));

        assertThat(saved).isEqualTo(link);
        assertThat(Files.isSymbolicLink(saved)).isFalse();
        assertThat(Files.readString(saved)).isEqualTo("new bytes");
        // The whole point: a truncating write would have clobbered the target instead.
        assertThat(Files.readString(secret)).isEqualTo("DISCORD_TOKEN=hunter2");
    }

    @Test
    void writeIntoAllowedRootOverwritesInPlaceRatherThanAccumulatingDuplicates(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));

        Path first = messageService.writeIntoAllowedRoot(root, "456", "poster.png", "v1".getBytes(StandardCharsets.UTF_8));
        Path second = messageService.writeIntoAllowedRoot(root, "456", "poster.png", "v2".getBytes(StandardCharsets.UTF_8));

        // Same attachment ID means the same path: the second write replaces the first
        // rather than leaving poster(1).png behind. Not idempotence — a real overwrite.
        assertThat(second).isEqualTo(first);
        assertThat(Files.readString(second)).isEqualTo("v2");
        try (var entries = Files.list(root)) {
            // Also pins that the temporary .part file did not survive the move.
            assertThat(entries).hasSize(1);
        }
    }

    @Test
    void writeIntoAllowedRootSavesGroupReadableFiles(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        if (!root.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Assumptions.abort("no POSIX file permissions on this host");
        }

        Path saved = messageService.writeIntoAllowedRoot(
                root, "321", "poster.png", "bytes".getBytes(StandardCharsets.UTF_8));

        // rw-r-----, not createTempFile's 0600 and not a direct write's 0644. Group read is
        // what makes a shared-group deployment possible at all — nothing about the directory
        // can grant read on a 0600 file after the fact. Not world-readable, because these are
        // Discord attachments landing on a possibly shared host.
        assertThat(Files.getPosixFilePermissions(saved))
                .containsExactlyInAnyOrder(PosixFilePermission.OWNER_READ, PosixFilePermission.OWNER_WRITE,
                        PosixFilePermission.GROUP_READ);
    }

    @Test
    void writeIntoAllowedRootLeavesTheArchivedCopyIntactWhenTheWriteCannotComplete(@TempDir Path dir) throws IOException {
        Path root = Files.createDirectory(dir.resolve("downloads"));
        Path archived = messageService.writeIntoAllowedRoot(
                root, "789", "poster.png", "the good copy".getBytes(StandardCharsets.UTF_8));

        // A directory at the target name makes the move fail the way a full disk or a
        // killed process would, after the point where the old code had already unlinked.
        Path blocked = root.resolve("789-blocker");
        Files.createDirectory(blocked);
        Files.writeString(blocked.resolve("child"), "keeps the directory non-empty");

        assertThatThrownBy(() -> messageService.writeIntoAllowedRoot(
                root, "789", "blocker", "replacement".getBytes(StandardCharsets.UTF_8)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Failed to save attachment");

        assertThat(Files.readString(archived)).isEqualTo("the good copy");
        try (var entries = Files.list(root)) {
            assertThat(entries).as("no .part file left behind").hasSize(2);
        }
    }

    private Message.Attachment attachment(String id, String fileName, int size) {
        return attachment(id, fileName, size, null);
    }

    private Message.Attachment attachment(String id, String fileName, int size, String url) {
        Message.Attachment attachment = mock(Message.Attachment.class);
        when(attachment.getId()).thenReturn(id);
        when(attachment.getFileName()).thenReturn(fileName);
        when(attachment.getSize()).thenReturn(size);
        if (url != null) {
            when(attachment.getUrl()).thenReturn(url);
        }
        return attachment;
    }

    @SuppressWarnings("unchecked")
    private void stubMessageWithAttachments(Message.Attachment... attachments) {
        TextChannel channel = stubChannel();
        Message message = mock(Message.class);
        RestAction<Message> retrieve = mock(RestAction.class);
        when(channel.retrieveMessageById(MESSAGE_ID)).thenReturn(retrieve);
        when(retrieve.complete()).thenReturn(message);
        when(message.getAttachments()).thenReturn(List.of(attachments));
    }

    private TextChannel stubChannel() {
        TextChannel channel = mock(TextChannel.class);
        when(jda.getTextChannelById(CHANNEL_ID)).thenReturn(channel);
        return channel;
    }

    @SuppressWarnings("unchecked")
    private RestAction<List<Message>> restAction(List<Message> messages) {
        RestAction<List<Message>> action = mock(RestAction.class);
        when(action.complete()).thenReturn(messages);
        return action;
    }

    private Message message(String messageId, String authorId, String username, String content) {
        User author = mock(User.class);
        when(author.getId()).thenReturn(authorId);
        when(author.getName()).thenReturn(username);

        Message message = mock(Message.class);
        when(message.getId()).thenReturn(messageId);
        when(message.getAuthor()).thenReturn(author);
        when(message.getTimeCreated()).thenReturn(OffsetDateTime.parse("2026-05-26T00:00:00Z"));
        when(message.getContentDisplay()).thenReturn(content);
        when(message.getAttachments()).thenReturn(List.of());
        return message;
    }
}
