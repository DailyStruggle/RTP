package io.github.dailystruggle.effectsapi.common;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for ADR-003, ADR-004, and ADR-116: no class under
 * {@code io.github.dailystruggle.effectsapi.common.**} may reference any
 * platform-specific or RTP-specific type ({@code org.bukkit.*},
 * {@code net.minecraft.*}, or {@code io.github.dailystruggle.rtp.*}).
 *
 * <p>Implemented as both a bytecode constant-pool scan against compiled {@code .class}
 * files and a source-import scan against {@code .java} files under
 * {@code effectsapi/common/**}.
 */
class EffectsApiCommonNoPlatformImportsTest {

    private static final String[] FORBIDDEN_PREFIXES = {
            "org/bukkit/",
            "net/minecraft/",
            "io/github/dailystruggle/rtp/"
    };

    private static final String[] FORBIDDEN_IMPORT_PREFIXES = {
            "org.bukkit.",
            "net.minecraft.",
            "io.github.dailystruggle.rtp."
    };

    @Test
    void commonPackageHasNoPlatformImports() throws IOException {
        Path commonRoot = locateCommonClassRoot();
        List<String> violations = new ArrayList<>();

        try (Stream<Path> walk = Files.walk(commonRoot)) {
            walk.filter(p -> p.toString().endsWith(".class"))
                .forEach(p -> {
                    try {
                        scanClassFile(p, commonRoot, violations);
                    } catch (IOException e) {
                        fail("Failed to read " + p + ": " + e.getMessage());
                    }
                });
        }

        if (!violations.isEmpty()) {
            fail("Platform or RTP imports leaked into effectsapi.common (ADR-003/ADR-004/ADR-116 violation):\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    @Test
    void commonSourcesHaveNoPlatformOrRtpImports() throws IOException {
        Path sourceRoot = Paths.get(System.getProperty("user.dir"))
                .resolve("src/main/java/io/github/dailystruggle/effectsapi/common")
                .toAbsolutePath()
                .normalize();
        assertTrue(Files.isDirectory(sourceRoot), "Could not locate effectsapi.common source root: " + sourceRoot);

        List<String> violations = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(sourceRoot)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                .forEach(p -> {
                    try {
                        for (String line : Files.readAllLines(p)) {
                            String trimmed = line.trim();
                            if (!trimmed.startsWith("import ")) continue;
                            for (String forbidden : FORBIDDEN_IMPORT_PREFIXES) {
                                if (trimmed.contains(forbidden)) {
                                    violations.add(sourceRoot.relativize(p) + " -> " + trimmed);
                                }
                            }
                        }
                    } catch (IOException e) {
                        fail("Failed to read " + p + ": " + e.getMessage());
                    }
                });
        }

        if (!violations.isEmpty()) {
            fail("Forbidden import statements in effectsapi.common (ADR-116 violation):\n  - "
                    + String.join("\n  - ", violations));
        }
    }

    private static Path locateCommonClassRoot() {
        // Find the Effect.class file via the classloader, then derive the package root.
        URL effectResource = EffectsApiCommonNoPlatformImportsTest.class
                .getClassLoader()
                .getResource("io/github/dailystruggle/effectsapi/common/Effect.class");
        assertTrue(effectResource != null,
                "Could not locate compiled Effect.class — was :effects-api:compileJava run?");
        Path effectClass = Paths.get(java.net.URI.create(effectResource.toString()));
        // Walk up to ".../classes/java/main/io/github/dailystruggle/effectsapi/common"
        return effectClass.getParent();
    }

    private static void scanClassFile(Path classFile, Path root, List<String> violations) throws IOException {
        byte[] bytes = Files.readAllBytes(classFile);
        java.io.DataInputStream in = new java.io.DataInputStream(new java.io.ByteArrayInputStream(bytes));

        int magic = in.readInt();
        if (magic != 0xCAFEBABE) return;
        in.readUnsignedShort(); // minor
        in.readUnsignedShort(); // major
        int cpCount = in.readUnsignedShort();

        for (int i = 1; i < cpCount; i++) {
            int tag = in.readUnsignedByte();
            switch (tag) {
                case 1: { // CONSTANT_Utf8
                    String s = in.readUTF();
                    for (String prefix : FORBIDDEN_PREFIXES) {
                        if (s.contains(prefix)) {
                            violations.add(root.relativize(classFile) + " → references '" + prefix
                                    + "' (in constant: " + truncate(s) + ")");
                        }
                    }
                    break;
                }
                case 7: case 8: case 16: case 19: case 20:
                    in.skipBytes(2); break;
                case 15:
                    in.skipBytes(3); break;
                case 3: case 4: case 9: case 10: case 11: case 12: case 17: case 18:
                    in.skipBytes(4); break;
                case 5: case 6:
                    in.skipBytes(8);
                    i++; // long/double take two cp slots
                    break;
                default:
                    // Unknown tag - bail out of this file rather than misalign.
                    return;
            }
        }
    }

    private static String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
