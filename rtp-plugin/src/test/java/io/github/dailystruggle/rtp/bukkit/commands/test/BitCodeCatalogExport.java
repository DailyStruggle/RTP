package io.github.dailystruggle.rtp.bukkit.commands.test;

import io.github.dailystruggle.bit.BitCodeRegistry;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodeCatalog;
import io.github.dailystruggle.rtp.common.bit.RtpBitCodes;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;

/** Command-line generator and freshness checker for the committed BIT catalog artifacts. */
public final class BitCodeCatalogExport {
    private BitCodeCatalogExport() {}

    public static void main(String[] args) throws IOException {
        if (args.length != 2 || !(args[0].equals("--write") || args[0].equals("--check"))) {
            throw new IllegalArgumentException("Usage: BitCodeCatalogExport --write|--check <repository-root>");
        }
        Path root = Path.of(args[1]);
        BitCodeRegistry registry = registry();
        String markdown = RtpBitCodeCatalog.markdown(registry);
        String json = RtpBitCodeCatalog.json(registry);
        Path markdownFile = root.resolve("docs/dev/BIT_CODES.md");
        Path jsonFile = root.resolve("docs/dev/bit-codes.json");
        if (args[0].equals("--write")) {
            Files.writeString(markdownFile, markdown, StandardCharsets.UTF_8);
            Files.writeString(jsonFile, json, StandardCharsets.UTF_8);
            return;
        }
        checkFile(markdownFile, markdown);
        checkFile(jsonFile, json);
    }

    static BitCodeRegistry registry() {
        return RtpBitCodes.initialiseOwners(BukkitBitCodeOwners.OWNERS);
    }

    static void check(Path root) throws IOException {
        Objects.requireNonNull(root, "root must not be null");
        main(new String[] {"--check", root.toAbsolutePath().toString()});
    }

    private static void checkFile(Path file, String expected) throws IOException {
        if (!Files.isRegularFile(file) || !Files.readString(file, StandardCharsets.UTF_8).equals(expected)) {
            throw new IllegalStateException(file + " is stale; run :rtp-plugin:generateBitCodes");
        }
    }
}
