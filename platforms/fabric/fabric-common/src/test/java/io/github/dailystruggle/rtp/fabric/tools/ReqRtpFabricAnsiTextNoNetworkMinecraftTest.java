package io.github.dailystruggle.rtp.fabric.tools;

import org.junit.jupiter.api.Test;

import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Regression guard for the rtp-fabric-ADR-002 invariant that the
 * Fabric console log path must not transitively load any
 * {@code net.minecraft.*} chat/network-protocol types.
 *
 * <p>Background - the original 26.1 crash:
 * <pre>
 * Caused by: java.lang.NoClassDefFoundError: net/minecraft/class_2561
 *   at FabricServerAccessor.log(FabricServerAccessor.java:501)
 *   at RTP.log(RTP.java:476)
 *   at SafetyTokenExpander.expandAndApply(...)
 *   at Configs.reloadConfigs(...)
 *   at FabricDatabaseHandler.setupDatabase(...)
 *   at RTPFabricMod.onInitialize(...)
 * </pre>
 *
 * <p>{@code class_2561} is the intermediary name for
 * {@code net.minecraft.network.chat.Component}. It is not exposed on
 * the Fabric loader classpath the same way it was pre-26.1
 * (rtp-fabric-ADR-007 / Mojmap deobfuscation), so any class touched by
 * {@code log()} that imports {@code Component} fails JVM verification
 * and aborts {@code onInitialize}.
 *
 * <p>The fix routes the early-log path through {@link FabricAnsiText}
 * - a pure-string utility with <b>no</b> {@code net.minecraft.*}
 * imports. This test asserts that property on the compiled class file,
 * so any future edit that pulls a {@code net.minecraft.*} symbol into
 * {@code FabricAnsiText} fails the build instead of crashing servers.
 */
public class ReqRtpFabricAnsiTextNoNetworkMinecraftTest {

    @Test
    public void fabricAnsiText_classFile_hasNoNetMinecraftReferences() throws Exception {
        Path classFile = locateClassFile();
        assertTrue(Files.isRegularFile(classFile),
                "Expected compiled FabricAnsiText.class on the test classpath: " + classFile);

        byte[] bytes = Files.readAllBytes(classFile);
        // Constant-pool UTF-8 entries are stored verbatim, so a raw byte
        // substring check is sufficient. Treat the file as ISO-8859-1 to
        // preserve byte alignment.
        String content = new String(bytes, StandardCharsets.ISO_8859_1);

        assertFalse(content.contains("net/minecraft/"),
                "FabricAnsiText must not reference any net/minecraft/* type "
                        + "(rtp-fabric-ADR-002). Such references would re-introduce "
                        + "the 26.1 NoClassDefFoundError: net/minecraft/class_2561 crash "
                        + "on the FabricServerAccessor.log path. Class file: " + classFile);
    }

    @Test
    public void fabricAnsiText_handlesRepresentativeInputsWithoutNetMinecraft() {
        // Quick functional sanity - the class itself must be loadable and
        // usable in a JVM that has no net.minecraft.* on the classpath
        // (which is true for our test JVM). Failing this would mean
        // FabricAnsiText is silently depending on something it shouldn't.
        assertEquals("", FabricAnsiText.toAnsiString(null));
        assertEquals("", FabricAnsiText.toAnsiString(""));
        assertEquals("", FabricAnsiText.stripColor(null));

        // §-coded legacy: should produce ANSI escapes plus a trailing reset.
        String ansi = FabricAnsiText.toAnsiString("\u00A7chello");
        assertTrue(ansi.contains("\u001b["), "Expected ANSI escape in: " + ansi);
        assertTrue(ansi.endsWith("\u001b[0m"), "Expected trailing reset in: " + ansi);

        // &-coded legacy: should be normalised through the &→§ pipeline.
        assertEquals("hello", FabricAnsiText.stripColor("&ahello"));

        // #RRGGBB hex: should emit a 24-bit truecolor escape.
        String hex = FabricAnsiText.toAnsiString("#ff8800x");
        assertTrue(hex.contains("\u001b[38;2;255;136;0m"),
                "Expected truecolor escape in: " + hex);
    }

    @Test
    public void fabricAnsiText_expandsMiniMessageTags_noRawTagsReachConsole() {
        // Regression for the scan-status / scanStart console lines, whose
        // messages.yml [P0] prefix is "<rainbow>[RTP]</rainbow>". On the
        // Adventure-less Fabric console these MiniMessage tags previously
        // printed literally (e.g. "<rainbow>[RTP]</rainbow> scan task started
        // for regions: default"). normalise() now runs MiniMessageColorExpander
        // so the tags expand to legacy/ANSI color instead of surviving as text.
        String prefixed = FabricAnsiText.toAnsiString("<rainbow>[RTP]</rainbow> scan task started");
        assertFalse(prefixed.contains("<rainbow>"),
                "rainbow tag must be expanded, not printed raw: " + prefixed);
        assertFalse(prefixed.contains("</rainbow>"),
                "closing rainbow tag must be consumed: " + prefixed);
        assertTrue(prefixed.contains("\u001b["),
                "Expected ANSI color escapes from the expanded rainbow: " + prefixed);

        // Named color tags expand too and must not survive literally.
        String named = FabricAnsiText.toAnsiString("<red>hi</red>");
        assertFalse(named.contains("<red>"), "named color tag must be expanded: " + named);
        assertTrue(named.contains("\u001b[91m"), "Expected red ANSI escape in: " + named);
    }

    /** Locate {@code FabricAnsiText.class} on the test runtime classpath. */
    private static Path locateClassFile() throws Exception {
        String resource = FabricAnsiText.class.getName().replace('.', '/') + ".class";
        URL url = FabricAnsiText.class.getClassLoader().getResource(resource);
        assertNotNull(url, "FabricAnsiText.class not on the test classpath");
        if ("file".equals(url.getProtocol())) {
            return Paths.get(url.toURI());
        }
        // Fallback: walk the standard Gradle output layout from the
        // module CWD (rtp-fabric/rtp-fabric-common).
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
