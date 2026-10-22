package io.github.dailystruggle.rtp.common.bit.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.bit.BitFinding;
import io.github.dailystruggle.effectsapi.common.EffectStage;
import io.github.dailystruggle.rtp.common.bit.BitSeverityPolicy;
import java.io.IOException;
import java.lang.System.Logger.Level;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("ADR-121 effects config PBIT (EffectsGroupValidator, RTP_EFFECTS_*)")
class EffectsGroupValidatorTest {
    private static final String DIR = EffectsGroupValidator.DIRECTORY + "/";
    private static final ConfigEnvironment WITH_EFFECTS =
            ConfigEnvironment.HEADLESS.withEffectNames(Set.of("SOUND", "PARTICLE", "POTION", "RIFT"));

    @BeforeEach
    @AfterEach
    void resetPolicy() {
        BitSeverityPolicy.resetDefaults();
    }

    private static List<BitFinding> run(ConfigEnvironment environment, String... pathAndText) {
        Map<String, String> files = new LinkedHashMap<>();
        for (int i = 0; i < pathAndText.length; i += 2) files.put(DIR + pathAndText[i], pathAndText[i + 1]);
        return ConfigPbit.run(EffectsGroupValidator.INSTANCE, ConfigSnapshot.of(files), environment).findings();
    }

    private static List<BitFinding> withCode(List<BitFinding> findings, String code) {
        return findings.stream().filter(f -> code.equals(f.code())).toList();
    }

    @Test
    @DisplayName("ADR-121 a clean group produces no finding")
    void cleanGroup() {
        List<BitFinding> findings = run(WITH_EFFECTS,
                "default.yml", "when: postteleport\neffects:\n  - SOUND.X.1\n",
                "vip.yml", "when: postteleport\npermission: rtp.vip\nplayers: [Steve]\ninherit: [default]\n"
                        + "effects:\n  - particle.portal.3\nversion: \"1.0\"\n");
        assertEquals(List.of(), findings);
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_UNKNOWN_KEY fires with a suggestion, staged to WARNING")
    void unknownKey() {
        List<BitFinding> findings = withCode(run(null, "a.yml", "when: preload\neffect:\n  - SOUND.X\n"),
                "RTP_EFFECTS_UNKNOWN_KEY");
        assertEquals(1, findings.size());
        assertEquals("effect", findings.get(0).path());
        assertEquals("effects", findings.get(0).suggestion());
        assertEquals(Level.WARNING, findings.get(0).severity());
        assertEquals(1600, findings.get(0).codeId());
    }

    @Test
    @DisplayName("ADR-121 staged RTP_EFFECTS_UNKNOWN_KEY is ERROR under strictConfig")
    void strictConfigRestoresError() {
        BitSeverityPolicy.load(false, true, Map.of());
        List<BitFinding> findings = withCode(run(null, "a.yml", "when: preload\nbogus: 1\n"),
                "RTP_EFFECTS_UNKNOWN_KEY");
        assertEquals(Level.ERROR, findings.get(0).severity());
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_MISSING_STAGE fires for an absent or blank when")
    void missingStage() {
        assertEquals(1, withCode(run(null, "a.yml", "effects: [SOUND.X]\n"), "RTP_EFFECTS_MISSING_STAGE").size());
        assertEquals(1, withCode(run(null, "a.yml", "when: \"\"\n"), "RTP_EFFECTS_MISSING_STAGE").size());
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_UNKNOWN_STAGE fires with a suggestion; stage case is ignored")
    void unknownStage() {
        List<BitFinding> findings = withCode(run(null, "a.yml", "when: postteleprot\n"), "RTP_EFFECTS_UNKNOWN_STAGE");
        assertEquals(1, findings.size());
        assertEquals("postteleport", findings.get(0).suggestion());
        assertEquals("when", findings.get(0).path());
        assertEquals(List.of(), run(null, "a.yml", "when: PostTeleport\n"));
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_STAGE_NOT_DISPATCHED warns for join stages")
    void stageNotDispatched() {
        List<BitFinding> findings = withCode(run(null, "a.yml", "when: firstjoin\n"),
                "RTP_EFFECTS_STAGE_NOT_DISPATCHED");
        assertEquals(1, findings.size());
        assertEquals(Level.WARNING, findings.get(0).severity());
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_BAD_TYPE fires for list when, map permission and map entries")
    void badType() {
        String text = "when:\n  - preload\n  - postload\npermission:\n  a: b\ninherit:\n  x: y\n"
                + "effects:\n  - a: b\n";
        assertTrue(ConfigSnapshot.of(Map.of(DIR + "a.yml", text)).unparsable().isEmpty());
        List<BitFinding> all = run(null, "a.yml", text);
        List<BitFinding> findings = withCode(all, "RTP_EFFECTS_BAD_TYPE");
        assertEquals(Set.of("when", "permission", "inherit", "effects[0]"),
                Set.copyOf(findings.stream().map(BitFinding::path).toList()), all.toString());
        assertTrue(withCode(run(null, "a.yml", "when: preload\nplayers: Steve\ninherit: []\n"),
                "RTP_EFFECTS_BAD_TYPE").isEmpty(), "scalar players and empty inherit are accepted");
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_INHERIT_UNRESOLVED fires for an undefined group; names are case-insensitive")
    void inheritUnresolved() {
        List<BitFinding> findings = withCode(run(null,
                "default.yml", "when: preload\n",
                "a.yml", "when: preload\ninherit: [Default, defualt-pre]\n"), "RTP_EFFECTS_INHERIT_UNRESOLVED");
        assertEquals(1, findings.size());
        assertEquals("inherit[1]", findings.get(0).path());
        assertEquals(Level.WARNING, findings.get(0).severity());
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_INHERIT_CYCLE is ERROR and reported once per cycle")
    void inheritCycle() {
        List<BitFinding> findings = withCode(run(null,
                "a.yml", "when: preload\ninherit: [b]\n",
                "b.yml", "when: preload\ninherit: [c]\n",
                "c.yml", "when: preload\ninherit: [a]\n",
                "self.yml", "when: preload\ninherit: self\n",
                "d.yml", "when: preload\ninherit: [a]\n"), "RTP_EFFECTS_INHERIT_CYCLE");
        assertEquals(2, findings.size());
        assertEquals(Level.ERROR, findings.get(0).severity());
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("a -> b -> c -> a")));
        assertTrue(findings.stream().anyMatch(f -> f.message().contains("self -> self")));
    }

    @Test
    @DisplayName("ADR-121 a diamond inherit is not a cycle")
    void diamondIsNotACycle() {
        assertEquals(List.of(), run(null,
                "base.yml", "when: preload\ninherit: []\n",
                "l.yml", "when: preload\ninherit: [base]\n",
                "r.yml", "when: preload\ninherit: [base]\n",
                "top.yml", "when: preload\ninherit: [l, r]\n"));
    }

    @Test
    @DisplayName("ADR-121 RTP_EFFECTS_UNKNOWN_EFFECT fires only when registered effects are known")
    void unknownEffect() {
        String group = "when: preload\neffects:\n  - SOUNDS.X.1\n  - COMMAND CONSOLE say hi\n  - RIFT.6.2\n";
        List<BitFinding> findings = withCode(run(WITH_EFFECTS, "a.yml", group), "RTP_EFFECTS_UNKNOWN_EFFECT");
        assertEquals(List.of("effects[0]", "effects[1]"), findings.stream().map(BitFinding::path).toList());
        assertEquals("SOUND", findings.get(0).suggestion());
        assertTrue(run(null, "a.yml", group).isEmpty(), "unknown registry skips the effect-name check");
    }

    @Test
    @DisplayName("ADR-121 effect type parsing follows EffectFactory grammar")
    void effectType() {
        assertEquals("SOUND", EffectsGroupValidator.effectType("sound.x.1"));
        assertEquals("COMMAND", EffectsGroupValidator.effectType("COMMAND CONSOLE say a.b"));
        assertEquals("A\\.B", EffectsGroupValidator.effectType("a\\.b.c"));
        assertEquals("DEATH", EffectsGroupValidator.effectType("DEATH"));
    }

    @Test
    @DisplayName("ADR-121 files outside definitions/effects are ignored")
    void otherDirectoriesIgnored() {
        Map<String, String> files = Map.of("effects/legacy.yml", "bogus: 1\n", "definitions/effects/sub/x.yml", "a: 1\n");
        assertEquals(List.of(), ConfigPbit.run(EffectsGroupValidator.INSTANCE, ConfigSnapshot.of(files),
                ConfigEnvironment.HEADLESS).findings());
    }

    @Test
    @DisplayName("ADR-121 the effects owner is registered with ConfigPbit")
    void registered() {
        assertTrue(ConfigPbit.checks().contains(EffectsGroupValidator.INSTANCE));
        assertEquals("config-effects", ConfigPbit.componentId(EffectsGroupValidator.INSTANCE));
    }

    @Test
    @DisplayName("ADR-121 shipped default effect groups and the Rift group produce no finding")
    void shippedGroupsAreClean() throws IOException {
        Path root = repositoryRoot();
        Map<String, String> files = new LinkedHashMap<>();
        try (Stream<Path> shipped = Files.list(root.resolve("rtp-plugin/src/main/resources/definitions/effects"))) {
            for (Path file : shipped.filter(p -> p.toString().endsWith(".yml")).toList()) {
                files.put(DIR + file.getFileName(), Files.readString(file, StandardCharsets.UTF_8));
            }
        }
        assertFalse(files.isEmpty(), "shipped effect groups must exist");
        files.put(DIR + "rift.yml", Files.readString(
                root.resolve("addons/LeafRTPRiftAddon/src/main/resources/rift.yml"), StandardCharsets.UTF_8));
        ConfigSnapshot snapshot = ConfigSnapshot.of(files);
        BitSeverityPolicy.load(false, true, Map.of());
        List<BitFinding> findings = ConfigPbit.run(EffectsGroupValidator.INSTANCE, snapshot, WITH_EFFECTS).findings();
        assertEquals(List.of(), findings);
    }

    @Test
    @DisplayName("ADR-121 section 3 drift: dispatched EffectStage tokens equal the platform handler stages")
    void stageVocabularyMatchesHandlers() throws IOException {
        Path root = repositoryRoot();
        Set<String> dispatched = new TreeSet<>();
        for (EffectStage stage : EffectStage.values()) {
            if (stage.dispatched()) dispatched.add(stage.token());
        }
        Set<String> bukkit = handlerStages(root.resolve(
                "rtp-plugin/src/main/java/io/github/dailystruggle/rtp/bukkit/effects/BukkitEffectsHandler.java"));
        assertEquals(dispatched, bukkit, "BukkitEffectsHandler fires every dispatched stage and no other");
        for (String other : List.of(
                "rtp-plugin/src/main/java/io/github/dailystruggle/rtp/fabric/effects/FabricEffectsHandler.java",
                "platforms/rtp-fabric/rtp-fabric-common-unobf/src/main/java/io/github/dailystruggle/rtp/fabric/unobf/effects/FabricEffectsHandlerUnobf.java",
                "platforms/rtp-neoforge/rtp-neoforge-common/src/main/java/io/github/dailystruggle/rtp/neoforge/effects/NeoForgeEffectsHandler.java")) {
            Path file = root.resolve(other);
            if (!Files.isRegularFile(file)) continue;
            Set<String> stages = handlerStages(file);
            assertFalse(stages.isEmpty(), other);
            assertTrue(EffectStage.tokens().containsAll(stages), other + " uses an undeclared stage: " + stages);
        }
    }

    private static Set<String> handlerStages(Path file) throws IOException {
        Matcher matcher = Pattern.compile("\"rtp\\.effect\\.([a-z]+)\"")
                .matcher(Files.readString(file, StandardCharsets.UTF_8));
        Set<String> out = new TreeSet<>();
        while (matcher.find()) out.add(matcher.group(1));
        return out;
    }

    private static Path repositoryRoot() {
        Path current = Path.of("").toAbsolutePath();
        while (current != null && !Files.isRegularFile(current.resolve("settings.gradle"))) {
            current = current.getParent();
        }
        if (current == null) throw new IllegalStateException("could not locate repository root");
        return current;
    }
}
