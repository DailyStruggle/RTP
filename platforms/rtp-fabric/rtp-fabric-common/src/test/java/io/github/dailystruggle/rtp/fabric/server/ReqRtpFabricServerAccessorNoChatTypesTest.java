package io.github.dailystruggle.rtp.fabric.server;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the rtp-fabric-ADR-002 / ADR-009 invariant that
 * {@code FabricServerAccessor} - which {@code RTPFabricMod} constructs
 * unconditionally on <em>every</em> runtime - must not reference the
 * version-volatile chat / boss-bar {@code net.minecraft.*} types.
 *
 * <p><b>Background - the 26.1 / 26.2 crash:</b>
 * <pre>
 * Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_2561
 *   at io.github.dailystruggle.rtp.fabric.RTPFabricMod.onInitialize(RTPFabricMod.java:85)
 * </pre>
 * Line 85 is {@code new FabricServerAccessor()}. {@code class_2561} is the
 * intermediary name for {@code net.minecraft.network.chat.Component}. The obf
 * carrier compiles its {@code net.minecraft.*} references to intermediary
 * names, which are absent on the deobf MC 26.x runtime (rtp-fabric-ADR-007).
 * When {@code FabricServerAccessor}'s own bytecode referenced
 * {@code Component} (the interactive-message and boss-bar paths), the JVM
 * verifier resolved {@code class_2561} while linking the accessor and crashed
 * mod init before {@code onInitialize} could finish.
 *
 * <p>The fix hoists those typed calls into obf-only helpers
 * ({@code FabricProgressBars}) and the per-player
 * {@code FabricInteractiveMessageSink} path, so the accessor's constant pool
 * carries no chat / boss-bar NM type. This test asserts that property on the
 * compiled class file: any future edit that pulls a chat / boss-bar
 * {@code net.minecraft.*} symbol back into {@code FabricServerAccessor} fails
 * the build instead of crashing servers.
 *
 * <p>Note: other {@code net.minecraft.*} references (e.g. {@code MinecraftServer},
 * {@code ServerLevel}, registries) remain allowed - they do not force eager
 * verifier resolution on the deobf runtime the way the chat/boss-bar subtype
 * checks did. The forbidden surface is specifically the chat and boss-bar
 * subtree.
 */
public class ReqRtpFabricServerAccessorNoChatTypesTest {

    @Test
    public void fabricServerAccessor_classFile_hasNoChatOrBossBarReferences() throws Exception {
        Path classFile = locateClassFile();
        assertTrue(Files.isRegularFile(classFile),
                "Expected compiled FabricServerAccessor.class on the test classpath: " + classFile);

        byte[] bytes = Files.readAllBytes(classFile);
        // Constant-pool UTF-8 entries are stored verbatim, so a raw byte
        // substring check is sufficient. Treat the file as ISO-8859-1 to
        // preserve byte alignment.
        String content = new String(bytes, StandardCharsets.ISO_8859_1);

        String[] forbidden = new String[] {
                "net/minecraft/network/chat/",
                "net/minecraft/server/level/ServerBossEvent",
                "net/minecraft/world/BossEvent"
        };
        for (String f : forbidden) {
            assertFalse(content.contains(f),
                    "FabricServerAccessor must not reference " + f + "* (rtp-fabric-ADR-002/009). "
                            + "Such references re-introduce the NoClassDefFoundError: "
                            + "net/minecraft/class_2561 crash on `new FabricServerAccessor()` "
                            + "on the deobf MC 26.x runtime. Route the typed call through an "
                            + "obf-only helper (FabricProgressBars) or the "
                            + "FabricInteractiveMessageSink path instead. Class file: " + classFile);
        }
    }

    /** Locate {@code FabricServerAccessor.class} on the test runtime classpath. */
    private static Path locateClassFile() throws Exception {
        String resource = FabricServerAccessor.class.getName().replace('.', '/') + ".class";
        URL url = FabricServerAccessor.class.getClassLoader().getResource(resource);
        assertNotNull(url, "FabricServerAccessor.class not on the test classpath");
        if ("file".equals(url.getProtocol())) {
            return Paths.get(url.toURI());
        }
        Path[] candidates = new Path[] {
                Paths.get("build", "classes", "java", "main", resource),
                Paths.get("rtp-fabric", "rtp-fabric-common", "build", "classes", "java", "main", resource)
        };
        for (Path p : candidates) {
            if (Files.isRegularFile(p)) return p;
        }
        return candidates[0];
    }
}
