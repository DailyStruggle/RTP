package io.github.dailystruggle.rtp.common.mock;

import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodeCatalog;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodeLock;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/** Shared consistency check for committed addon-specific BIT catalogs and release locks. */
public final class BitCatalogFiles {
    private BitCatalogFiles() {}

    public static void check(Path addonRoot, BitCodeRegistry registry, String namespace) throws IOException {
        Path docs = addonRoot.resolve("docs");
        Path markdownFile = docs.resolve("BIT_CODES.md");
        Path lockFile = docs.resolve("bit-codes.lock");
        boolean update = "1".equals(System.getenv("RTP_BIT_UPDATE"));
        String markdown = RtpBitCodeCatalog.markdown(registry, namespace, addonRoot.getFileName() + " BIT Codes");
        List<RtpBitCodeLock.Entry> entries = Files.exists(lockFile)
                ? RtpBitCodeLock.parse(Files.readString(lockFile, StandardCharsets.UTF_8)) : List.of();
        if (update) {
            List<RtpBitCodeLock.Entry> updated = RtpBitCodeLock.update(registry, entries, namespace);
            Files.createDirectories(docs);
            Files.writeString(markdownFile, markdown, StandardCharsets.UTF_8);
            Files.writeString(lockFile, RtpBitCodeLock.render(updated), StandardCharsets.UTF_8);
            return;
        }
        if (!Files.isRegularFile(markdownFile) || !Files.isRegularFile(lockFile)) {
            throw new AssertionError("missing addon BIT catalog files under " + docs + "; run with RTP_BIT_UPDATE=1");
        }
        if (!markdown.equals(Files.readString(markdownFile, StandardCharsets.UTF_8))) {
            throw new AssertionError("stale addon BIT markdown catalog: " + markdownFile);
        }
        if (entries.stream().anyMatch(entry -> !entry.namespace().equalsIgnoreCase(namespace))) {
            throw new AssertionError("addon BIT lock contains rows outside namespace " + namespace + ": " + lockFile);
        }
        List<String> violations = RtpBitCodeLock.violations(registry, entries, namespace);
        if (!violations.isEmpty()) {
            throw new AssertionError("addon BIT lock violations: " + String.join("; ", violations));
        }
        if (!RtpBitCodeLock.render(entries).equals(Files.readString(lockFile, StandardCharsets.UTF_8))) {
            throw new AssertionError("non-canonical addon BIT lock: " + lockFile);
        }
    }
}
