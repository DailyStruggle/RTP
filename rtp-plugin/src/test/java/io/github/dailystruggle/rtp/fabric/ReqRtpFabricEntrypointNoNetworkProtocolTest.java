package io.github.dailystruggle.rtp.fabric;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the rtp-fabric-ADR-002 invariant that
 * {@code RTPFabricMod} (the Fabric mod entrypoint declared in
 * {@code fabric.mod.json}) must not reference {@code net.minecraft}
 * chat / network-protocol types.
 *
 * <p><b>Why:</b> on MC 26.1 (the first deobfuscated release,
 * see rtp-fabric-ADR-007) the intermediary name
 * {@code class_2596} (i.e. {@code net.minecraft.network.protocol.Packet})
 * is no longer exposed on the runtime classpath the way prior
 * versions exposed it. If {@code RTPFabricMod} statically references
 * any subtype of {@code Packet} (e.g. {@code ClientboundSystemChatPacket}),
 * JVM class verification fails on entrypoint load with
 * {@code NoClassDefFoundError: net/minecraft/class_2596} and the server
 * never reaches {@code onInitialize}.
 *
 * <p>The original 2026-05-08 crash report:
 * <pre>
 * Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_2596
 *   at java.base/java.lang.Class.forName0(Native Method)
 *   ...
 *   at net.fabricmc.loader.impl.entrypoint.EntrypointStorage$NewEntry.getOrCreate(...)
 * </pre>
 *
 * <p>This test scans the compiled {@code RTPFabricMod.class} constant pool
 * (UTF-8 entries) for any reference to forbidden NM packages. We use a
 * raw byte-substring search rather than ASM to avoid pulling a new test
 * dependency - the constant pool stores type names as plain UTF-8.
 *
 * <p>Allowed: {@code net.minecraft.commands.CommandSourceStack} and
 * {@code net.minecraft.server.level.ServerPlayer} are used by the private
 * helper methods {@code resolveSenderUuid} and {@code checkBrigadierPermission}
 * (passed as method references to the {@code commands-api} Brigadier bridge).
 * Their intermediary names ({@code class_2168}, {@code class_3222}) have
 * been stable across all currently-supported MC versions including 26.1.
 * The forbidden surface is specifically the chat / network-protocol
 * subtree where {@code Packet} drift bites.
 */
public class ReqRtpFabricEntrypointNoNetworkProtocolTest {

    /** Forbidden internal-form prefixes (slash-separated, JVM constant-pool form). */
    private static final List<String> FORBIDDEN_INTERNAL_PREFIXES = List.of(
            "net/minecraft/network/protocol/",
            "net/minecraft/network/chat/"
    );

    @Test
    public void rtpFabricMod_classFile_doesNotReferenceNetworkProtocolOrChatTypes() throws Exception {
        Path classFile = locateClassFile();
        assertTrue(Files.isRegularFile(classFile),
                "Expected compiled RTPFabricMod.class at " + classFile
                        + " — run :rtp-plugin:compileJava first.");

        byte[] bytes = Files.readAllBytes(classFile);
        // Constant-pool UTF-8 entries are stored verbatim, so a raw byte
        // substring search is sufficient. We treat the whole class file
        // as ISO_8859_1 to preserve byte alignment.
        String content = new String(bytes, StandardCharsets.ISO_8859_1);

        for (String forbidden : FORBIDDEN_INTERNAL_PREFIXES) {
            assertFalse(content.contains(forbidden),
                    "RTPFabricMod must not reference " + forbidden + "* (rtp-fabric-ADR-002). "
                            + "Such references trigger NoClassDefFoundError: net/minecraft/class_2596 "
                            + "on MC 26.1 entrypoint load. Found in: " + classFile);
        }
    }

    /**
     * Locate {@code RTPFabricMod.class} under the rtp-plugin module's
     * compiled output. Tries the standard Gradle layout first, then
     * the IntelliJ {@code out/} layout as a fallback.
     */
    private static Path locateClassFile() {
        String rel = "io/github/dailystruggle/rtp/fabric/RTPFabricMod.class";
        // Try several candidate roots, in order:
        //   1. CWD-relative Gradle output (`:rtp-plugin:test` runs with
        //      working directory == rtp-plugin/, so build/... resolves there).
        //   2. Repo-root-relative Gradle output (when the test runner is
        //      launched from the project root).
        //   3. IntelliJ default output (`out/production/classes`), CWD
        //      and repo-root variants.
        Path[] candidates = new Path[] {
                Paths.get("build", "classes", "java", "main", rel),
                Paths.get("rtp-plugin", "build", "classes", "java", "main", rel),
                Paths.get("out", "production", "classes", rel),
                Paths.get("rtp-plugin", "out", "production", "classes", rel)
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        // Fallback - return the first candidate so the assertion failure
        // message points at the expected Gradle location.
        return candidates[0];
    }
}
