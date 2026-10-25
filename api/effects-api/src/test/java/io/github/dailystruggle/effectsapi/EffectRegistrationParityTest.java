package io.github.dailystruggle.effectsapi;

import io.github.dailystruggle.effectsapi.bukkit.BukkitValueCoercer;
import io.github.dailystruggle.effectsapi.common.Effect;
import io.github.dailystruggle.effectsapi.common.EffectFactory;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies ADR-116 (RTP-70): every platform initializer delegates to
 * {@link EffectFactory#registerBuiltInEffects} and exposes the full built-in
 * effect key set ({@code TITLE}, {@code GLIDE}, {@code COMMAND}, {@code DEATH},
 * {@code DROP_INVENTORY}, {@code DROP_EXP}, {@code SOUND}, {@code PARTICLE},
 * {@code POTION}, {@code FIREWORK}, {@code NOTE}) with zero platform gaps.
 */
@DisplayName("RTP-70 / ADR-116: built-in effect registration matches across Bukkit, Fabric, Fabric 26.x and NeoForge")
class EffectRegistrationParityTest {

    private static final Pattern ADD_OR_PUT_EFFECT =
            Pattern.compile("EffectFactory\\.(?:addEffect|putEffect)\\(\\s*\"([A-Z_]+)\"");
    private static final Pattern REGISTER_BUILT_IN =
            Pattern.compile("EffectFactory\\.registerBuiltInEffects\\s*\\(");

    /** Initializer source per platform, relative to the effects-api project directory. */
    private static final Map<String, String> INITIALIZERS = new LinkedHashMap<>();

    /**
     * Declared gaps per platform. Under ADR-116 all four platforms register the
     * complete built-in effect set with zero gaps.
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
        DECLARED_GAPS.put("fabric", Set.of());
        DECLARED_GAPS.put("fabric-26.x", Set.of());
        DECLARED_GAPS.put("neoforge", Set.of());
    }

    @Test
    @DisplayName("each platform initializer delegates to EffectFactory.registerBuiltInEffects and exposes the full built-in key set")
    void registrationsMatchExceptDeclaredGaps() throws IOException {
        Map<String, Set<String>> registered = new LinkedHashMap<>();
        Set<String> union = new TreeSet<>(EffectFactory.BUILT_IN_EFFECT_KEYS);
        union.addAll(EffectFactory.BUILT_IN_EFFECT_ALIASES);

        for (Map.Entry<String, String> e : INITIALIZERS.entrySet()) {
            Set<String> names = scan(e.getKey(), e.getValue());
            assertTrue(names.containsAll(EffectFactory.BUILT_IN_EFFECT_KEYS),
                    e.getKey() + ": missing built-in effect keys: " + names);
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

    @Test
    @DisplayName("EffectFactory.registerBuiltInEffects() registers all 11 built-in keys and aliases")
    void registerBuiltInEffectsRegistersAllCanonicalKeys() {
        EffectFactory.setCoercer(new BukkitValueCoercer());
        EffectFactory.registerBuiltInEffects();

        Set<String> actual = new TreeSet<>(EffectFactory.registeredNames());
        for (String key : EffectFactory.BUILT_IN_EFFECT_KEYS) {
            assertTrue(actual.contains(key), "registerBuiltInEffects() must register " + key);
            assertNotNull(EffectFactory.buildEffect(key), "EffectFactory.buildEffect(" + key + ") must succeed");
        }
        for (String alias : EffectFactory.BUILT_IN_EFFECT_ALIASES) {
            assertTrue(actual.contains(alias), "registerBuiltInEffects() must register alias " + alias);
            assertNotNull(EffectFactory.buildEffect(alias), "EffectFactory.buildEffect(" + alias + ") must succeed");
        }
    }

    @Test
    @DisplayName("EffectFactory.reportUnsupportedPrimitive logs once per platform+primitive pair")
    void reportUnsupportedPrimitiveLogsOncePerPrimitive() {
        EffectFactory.clearUnsupportedPrimitives();
        List<String> warnings = new ArrayList<>();
        Effect.setDefaultWarn(warnings::add);
        try {
            EffectFactory.reportUnsupportedPrimitive("NeoForge", "NOTE", "test reason");
            EffectFactory.reportUnsupportedPrimitive("NeoForge", "NOTE", "duplicate reason");
            assertEquals(1, warnings.size(), "must warn only once per (platform, primitive)");
            assertTrue(EffectFactory.unsupportedPrimitives().contains("NEOFORGE:NOTE"));
        } finally {
            EffectFactory.clearUnsupportedPrimitives();
            Effect.setDefaultWarn(msg -> System.err.println("[effects-api] " + msg));
        }
    }

    @Test
    @DisplayName("BukkitEffectsInitializer.registerAll() registers the full common effect key set")
    void bukkitEffectsInitializerRegistersExpectedCommonKeySet() throws Exception {
        java.lang.reflect.Field intVersionField = EffectsAPI.class.getDeclaredField("intVersion");
        intVersionField.setAccessible(true);
        Integer priorVersion = (Integer) intVersionField.get(null);

        java.lang.reflect.Field registeredField =
                io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer.class.getDeclaredField("REGISTERED");
        registeredField.setAccessible(true);
        java.util.concurrent.atomic.AtomicBoolean registeredFlag =
                (java.util.concurrent.atomic.AtomicBoolean) registeredField.get(null);
        boolean priorRegistered = registeredFlag.getAndSet(false);

        try {
            intVersionField.set(null, 20);
            io.github.dailystruggle.effectsapi.bukkit.BukkitEffectsInitializer.registerAll();

            Set<String> expected = Set.of(
                    "SOUND", "PARTICLE", "POTION", "TITLE", "GLIDE", "COMMAND",
                    "DROP_INVENTORY", "DROP_EXP", "DROP_EXPERIENCE", "DEATH", "KILL",
                    "FIREWORK", "NOTE"
            );
            Set<String> actual = new TreeSet<>(io.github.dailystruggle.effectsapi.common.EffectFactory.registeredNames());
            for (String key : expected) {
                assertTrue(actual.contains(key),
                        "BukkitEffectsInitializer.registerAll() must register " + key + " (actual: " + actual + ")");
            }
        } finally {
            intVersionField.set(null, priorVersion);
            registeredFlag.set(priorRegistered);
        }
    }

    private static Set<String> scan(String platform, String relative) throws IOException {
        Path file = projectDir().resolve(relative).normalize();
        assertTrue(Files.isRegularFile(file), "initializer not found: " + file);
        String source = Files.readString(file, StandardCharsets.UTF_8);
        Set<String> names = new TreeSet<>();
        assertTrue(REGISTER_BUILT_IN.matcher(source).find(),
                platform + " initializer must delegate to EffectFactory.registerBuiltInEffects(...)");
        names.addAll(EffectFactory.BUILT_IN_EFFECT_KEYS);
        names.addAll(EffectFactory.BUILT_IN_EFFECT_ALIASES);
        Matcher m = ADD_OR_PUT_EFFECT.matcher(source);
        while (m.find()) names.add(m.group(1));
        return names;
    }

    private static Path projectDir() {
        // Gradle runs tests from the project directory (api/effects-api).
        return Paths.get(System.getProperty("user.dir")).toAbsolutePath();
    }
}
