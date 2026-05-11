package io.github.dailystruggle.effectsapi.fabric;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Regression guard for effects-api-ADR-006 (Fabric obf/unobf split):
 * the two carrier source trees — {@code effectsapi.fabric.*} (intermediary,
 * lives in {@code effects-api/src/main}) and {@code effectsapi.fabric_unobf.*}
 * (Mojmap, lives in the sibling module {@code effects-api-fabric-unobf}) —
 * must remain mutually independent at the source level. Neither side may
 * reference the other; if it did, the cross-mappings link would explode at
 * runtime when the wrong carrier is loaded.
 *
 * <p>Source-scan based (no ASM dependency); reads {@code package} and
 * {@code import} declarations from {@code .java} files and asserts the
 * disjointness contract.</p>
 *
 * <p>Trace: effects-api-ADR-006 §6 (regression tests).</p>
 */
@DisplayName("effects-api-ADR-006 — fabric/fabric_unobf carriers are mutually disjoint")
class EffectsApiFabricCarriersDisjointTest {

    private static final String OBF_PKG = "io.github.dailystruggle.effectsapi.fabric";
    private static final String UNOBF_PKG = "io.github.dailystruggle.effectsapi.fabric_unobf";

    @Test
    @DisplayName("effectsapi.fabric.* sources do not import effectsapi.fabric_unobf.*")
    void obfDoesNotReferenceUnobf() throws IOException {
        Path root = projectDir().resolve("src/main/java/io/github/dailystruggle/effectsapi/fabric");
        assertTrue(Files.isDirectory(root), "obf carrier root not found: " + root);
        List<String> offenders = scanForForbiddenImport(root, UNOBF_PKG);
        if (!offenders.isEmpty()) {
            fail("effectsapi.fabric.* must not reference fabric_unobf.* — offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    @DisplayName("effectsapi.fabric_unobf.* sources do not import effectsapi.fabric.*")
    void unobfDoesNotReferenceObf() throws IOException {
        // Sibling module path, resolved from effects-api/ working dir.
        Path root = projectDir().resolve("effects-api-fabric-unobf/src/main/java/io/github/dailystruggle/effectsapi/fabric_unobf").normalize();
        assertTrue(Files.isDirectory(root), "unobf carrier root not found: " + root);
        List<String> offenders = scanForForbiddenImport(root, OBF_PKG);
        if (!offenders.isEmpty()) {
            fail("effectsapi.fabric_unobf.* must not reference fabric.* — offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    private static Path projectDir() {
        // Gradle sets cwd to the project dir; fall back to user.dir.
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    /**
     * Walks {@code root} looking for any {@code .java} file whose
     * {@code import} list contains an entry starting with {@code forbiddenPkg + "."}
     * (or equal to it). Returns relative paths of offending files (with the
     * offending import line) for diagnostic output.
     */
    private static List<String> scanForForbiddenImport(Path root, String forbiddenPkg) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String line : Files.readAllLines(p)) {
                                String trimmed = line.trim();
                                if (!trimmed.startsWith("import ")) {
                                    // Once we hit the first non-import, non-package, non-comment, non-blank line, stop.
                                    if (!trimmed.isEmpty()
                                            && !trimmed.startsWith("package ")
                                            && !trimmed.startsWith("//")
                                            && !trimmed.startsWith("/*")
                                            && !trimmed.startsWith("*")
                                            && !trimmed.startsWith("@")) {
                                        break;
                                    }
                                    continue;
                                }
                                // import [static] <fqn>;
                                String body = trimmed.substring("import ".length()).trim();
                                if (body.startsWith("static ")) body = body.substring("static ".length()).trim();
                                if (body.endsWith(";")) body = body.substring(0, body.length() - 1).trim();
                                if (body.equals(forbiddenPkg) || body.startsWith(forbiddenPkg + ".")) {
                                    offenders.add(root.relativize(p) + " :: " + trimmed);
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        return offenders;
    }
}
