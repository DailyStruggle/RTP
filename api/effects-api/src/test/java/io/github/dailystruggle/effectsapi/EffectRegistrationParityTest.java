package io.github.dailystruggle.effectsapi;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every platform registers every built-in effect name unless the name is a declared gap (RTP-70).
 * The four initializers are hand-written lists; without this guard a name added on one platform
 * silently stays missing on the others, and its tokens are skipped there as unknown effects.
 *
 * <p>Source scan of the {@code EffectFactory.addEffect("NAME", ...)} calls, because the
 * initializers cannot run outside their server. ADR-116 replaces the lists (and this test) with
 * one registration in effects-api.
 */
@DisplayName("RTP-70: built-in effect registration matches across Bukkit, Fabric, Fabric 26.x and NeoForge")
class EffectRegistrationParityTest {

    private static final Pattern ADD_EFFECT = Pattern.compile("EffectFactory\\.addEffect\\(\\s*\"([A-Z_]+)\"");

    /** Initializer source per platform, relative to the effects-api project directory. */
    private static final Map<String, String> INITIALIZERS = new LinkedHashMap<>();

    /**
     * Names a platform may lack, because its handle method has no implementation yet. Listing a
     * name that the platform does register fails the test, so fixed gaps leave this list.
     */
    private static final Map<String, Set<String>> DECLARED_GAPS = new LinkedHashMap<>();

    static {
        INITIALIZERS.put("bukkit",
                "src/main/java/io/github/dailystruggle/effectsapi/bukkit/BukkitEffectsInitializer.java");
        INITIALIZERS.put("fabric",
                "../../platforms/rtp-fabric/rtp-fabric-common/src/main/java/io/github/dailystruggle/rtp/fabric/effects/FabricEffectsInitializer.java");
        INITIALIZERS.put("fabric-26.x",
                "effects-api-fabric-unobf/src/main/java/io/github/dailystruggle/effectsapi/fabric_unobf/FabricEffectsInitializer.java");
        INITIALIZERS.put("neoforge",
                "../../platforms/rtp-neoforge/rtp-neoforge-common/src/main/java/io/github/dailystruggle/rtp/neoforge/effects/local/NeoForgeEffectsInitializer.java");

        DECLARED_GAPS.put("bukkit", Set.of());
        DECLARED_GAPS.put("fabric", Set.of("FIREWORK", "NOTE"));
        DECLARED_GAPS.put("fabric-26.x", Set.of("FIREWORK", "NOTE"));
        DECLARED_GAPS.put("neoforge", Set.of("FIREWORK", "NOTE"));
    }

    @Test
    @DisplayName("each platform registers the union of built-in names minus its declared gaps")
    void registrationsMatchExceptDeclaredGaps() throws IOException {
        Map<String, Set<String>> registered = new LinkedHashMap<>();
        Set<String> union = new TreeSet<>();
        for (Map.Entry<String, String> e : INITIALIZERS.entrySet()) {
            Set<String> names = scan(e.getValue());
            assertTrue(names.size() >= 5, e.getKey() + ": scan found too few addEffect calls: " + names);
            registered.put(e.getKey(), names);
            union.addAll(names);
        }

        StringBuilder problems = new StringBuilder();
        for (Map.Entry<String, Set<String>> e : registered.entrySet()) {
            Set<String> gaps = DECLARED_GAPS.get(e.getKey());
            Set<String> missing = new TreeSet<>(union);
            missing.removeAll(e.getValue());
            missing.removeAll(gaps);
            if (!missing.isEmpty()) {
                problems.append(e.getKey()).append(" is missing ").append(missing).append('\n');
            }
            Set<String> staleGaps = new TreeSet<>(gaps);
            staleGaps.retainAll(e.getValue());
            if (!staleGaps.isEmpty()) {
                problems.append(e.getKey()).append(" registers declared gaps ").append(staleGaps)
                        .append("; remove them from DECLARED_GAPS and the EVENTS_AND_EFFECTS.md table\n");
            }
        }
        assertEquals("", problems.toString());
    }

    private static Set<String> scan(String relative) throws IOException {
        Path file = projectDir().resolve(relative).normalize();
        assertTrue(Files.isRegularFile(file), "initializer not found: " + file);
        Matcher m = ADD_EFFECT.matcher(Files.readString(file, StandardCharsets.UTF_8));
        Set<String> names = new TreeSet<>();
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static Path projectDir() {
        // Gradle runs tests from the project directory (api/effects-api).
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}
