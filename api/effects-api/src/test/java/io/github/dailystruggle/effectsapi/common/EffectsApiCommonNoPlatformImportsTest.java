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
 * Regression guard for ADR-003 + ADR-004: no class under
 * {@code io.github.dailystruggle.effectsapi.common.**} may reference any
 * platform-specific type ({@code org/bukkit/**} or {@code net/minecraft/**}).
 *
 * <p>Implemented as a constant-pool scan against compiled {@code .class} files —
 * no ArchUnit dependency. The scan walks every UTF-8 entry in each class file's
 * constant pool (CONSTANT_Utf8 = tag 1) and fails if any references a forbidden
 * package prefix.
 */
class EffectsApiCommonNoPlatformImportsTest {

    private static final String[] FORBIDDEN_PREFIXES = {
            "org/bukkit/",
            "net/minecraft/"
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
            fail("Platform imports leaked into effectsapi.common (ADR-003/ADR-004 violation):\n  - "
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
                    // Unknown tag — bail out of this file rather than misalign.
                    return;
            }
        }
    }

    private static String truncate(String s) {
        return s.length() > 80 ? s.substring(0, 80) + "…" : s;
    }
}
