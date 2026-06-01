package io.github.dailystruggle.rtp.fabric.unobf;

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
 * Regression guard for rtp-fabric-ADR-009 (Fabric obf/unobf common split):
 *
 * <ol>
 *   <li>{@code rtp.fabric.*} (intermediary, in {@code rtp-fabric-common}) and
 *       {@code rtp.fabric.unobf.*} (Mojmap, in the sibling module
 *       {@code rtp-fabric-common-unobf}) must remain mutually disjoint at the
 *       source level.</li>
 *   <li>The pre-26 adapter modules ({@code rtp-fabric-v1_20_R1},
 *       {@code rtp-fabric-v1_21_R1}, {@code rtp-fabric-v1_21_R5},
 *       {@code rtp-fabric-v1_21_R11}) must not reference any class under
 *       {@code rtp.fabric.unobf.*} — they live on the obf carrier exclusively.</li>
 * </ol>
 *
 * <p>Source-scan based (no ASM dependency); reads {@code import} declarations
 * from {@code .java} files.</p>
 *
 * <p>Trace: rtp-fabric-ADR-009 step 6 (regression tests).</p>
 */
@DisplayName("rtp-fabric-ADR-009 — common/common-unobf carriers are mutually disjoint")
class RtpFabricCarriersDisjointTest {

    private static final String OBF_PKG = "io.github.dailystruggle.rtp.fabric";
    private static final String UNOBF_PKG = "io.github.dailystruggle.rtp.fabric.unobf";

    @Test
    @DisplayName("rtp-fabric-common (rtp.fabric.*) does not import rtp.fabric.unobf.*")
    void commonDoesNotReferenceUnobf() throws IOException {
        Path root = projectDir().resolve("src/main/java/io/github/dailystruggle/rtp/fabric");
        assertTrue(Files.isDirectory(root), "common source root not found: " + root);
        // Skip the unobf subtree itself when scanning common — but unobf lives in
        // a sibling module, not under common, so the recursion is naturally clean.
        List<String> offenders = scanForForbiddenImport(root, UNOBF_PKG, /*excludeUnobfSubtree=*/false);
        if (!offenders.isEmpty()) {
            fail("rtp-fabric-common must not reference rtp.fabric.unobf.* — offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    @DisplayName("rtp-fabric-common-unobf (rtp.fabric.unobf.*) does not import non-unobf rtp.fabric.* (obf carrier)")
    void unobfDoesNotReferenceObfCommon() throws IOException {
        Path root = projectDir().resolve("../rtp-fabric-common-unobf/src/main/java/io/github/dailystruggle/rtp/fabric/unobf").normalize();
        assertTrue(Files.isDirectory(root), "unobf source root not found: " + root);
        // The obf carrier lives at io.github.dailystruggle.rtp.fabric.* (everything
        // EXCEPT the .unobf subpackage). We scan for imports that start with the
        // obf prefix but are not themselves under .unobf.
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String imp : importsOf(p)) {
                                if ((imp.equals(OBF_PKG) || imp.startsWith(OBF_PKG + "."))
                                        && !imp.startsWith(UNOBF_PKG + ".")
                                        && !imp.equals(UNOBF_PKG)) {
                                    offenders.add(root.relativize(p) + " :: import " + imp);
                                }
                            }
                        } catch (IOException e) {
                            throw new RuntimeException(e);
                        }
                    });
        }
        if (!offenders.isEmpty()) {
            fail("rtp-fabric-common-unobf must not reference the obf carrier rtp.fabric.* — offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    @Test
    @DisplayName("pre-26 adapter modules do not reference rtp.fabric.unobf.*")
    void pre26AdaptersDoNotReferenceUnobf() throws IOException {
        String[] modules = {
                "rtp-fabric-v1_20_R1",
                "rtp-fabric-v1_21_R1",
                "rtp-fabric-v1_21_R5",
                "rtp-fabric-v1_21_R11"
        };
        List<String> offenders = new ArrayList<>();
        for (String mod : modules) {
            Path root = projectDir().resolve("../" + mod + "/src/main/java").normalize();
            if (!Files.isDirectory(root)) {
                // Module may not exist in this checkout — skip rather than fail.
                continue;
            }
            offenders.addAll(prefixed(mod, scanForForbiddenImport(root, UNOBF_PKG, false)));
        }
        if (!offenders.isEmpty()) {
            fail("pre-26 fabric adapter modules must not reference rtp.fabric.unobf.* — offenders:\n  "
                    + String.join("\n  ", offenders));
        }
    }

    private static List<String> prefixed(String prefix, List<String> in) {
        List<String> out = new ArrayList<>(in.size());
        for (String s : in) out.add(prefix + "/" + s);
        return out;
    }

    private static Path projectDir() {
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }

    private static List<String> importsOf(Path javaFile) throws IOException {
        List<String> out = new ArrayList<>();
        for (String line : Files.readAllLines(javaFile)) {
            String trimmed = line.trim();
            if (!trimmed.startsWith("import ")) {
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
            String body = trimmed.substring("import ".length()).trim();
            if (body.startsWith("static ")) body = body.substring("static ".length()).trim();
            if (body.endsWith(";")) body = body.substring(0, body.length() - 1).trim();
            out.add(body);
        }
        return out;
    }

    private static List<String> scanForForbiddenImport(Path root, String forbiddenPkg, boolean unused) throws IOException {
        List<String> offenders = new ArrayList<>();
        try (Stream<Path> walk = Files.walk(root)) {
            walk.filter(p -> p.toString().endsWith(".java"))
                    .forEach(p -> {
                        try {
                            for (String imp : importsOf(p)) {
                                if (imp.equals(forbiddenPkg) || imp.startsWith(forbiddenPkg + ".")) {
                                    offenders.add(root.relativize(p) + " :: import " + imp);
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
