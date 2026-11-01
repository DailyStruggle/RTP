package io.github.dailystruggle.rtp.proxy.common;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Enforces rtp-proxy-ADR-001 section Package Boundary Rules / REQ-RTP-PROXY-COMMON-001.
 * The {@code rtp-proxy-common} module shall not import any proxy-vendor or
 * backend-platform class. This test walks the production source tree and
 * fails the build if any forbidden FQN prefix appears in an {@code import}
 * statement.
 */
class NoVendorImportsTest {

    private static final Pattern FORBIDDEN = Pattern.compile(
            "^\\s*import\\s+(static\\s+)?("
                    + "com\\.velocitypowered\\."
                    + "|net\\.md_5\\.bungee\\."
                    + "|org\\.bukkit\\."
                    + "|net\\.minecraft\\."
                    + "|net\\.fabricmc\\."
                    + ")"
    );

    @Test
    @DisplayName("rtp-proxy-common has no vendor imports (REQ-RTP-PROXY-COMMON-001)")
    void noVendorImports() throws IOException {
        Path srcMain = Path.of("src", "main", "java");
        // Resolve relative to the module dir; tests run with the module as CWD.
        if (!Files.isDirectory(srcMain)) {
            // Gradle Test default CWD is the module root; bail out gracefully if not.
            fail("Expected src/main/java to exist at " + srcMain.toAbsolutePath());
        }

        List<String> violations = new ArrayList<>();
        try (Stream<Path> files = Files.walk(srcMain)) {
            files.filter(p -> p.toString().endsWith(".java")).forEach(p -> {
                try {
                    List<String> lines = Files.readAllLines(p, StandardCharsets.UTF_8);
                    for (int i = 0; i < lines.size(); i++) {
                        if (FORBIDDEN.matcher(lines.get(i)).find()) {
                            violations.add(p + ":" + (i + 1) + " " + lines.get(i).trim());
                        }
                    }
                } catch (IOException e) {
                    throw new RuntimeException(e);
                }
            });
        }
        assertTrue(violations.isEmpty(),
                "Forbidden vendor imports detected:\n  " + String.join("\n  ", violations));
    }
}
