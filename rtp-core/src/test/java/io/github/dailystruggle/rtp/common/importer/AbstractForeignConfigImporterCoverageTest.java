package io.github.dailystruggle.rtp.common.importer;

import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlConfig;
import io.github.dailystruggle.rtp.common.configuration.yaml.RtpYamlSection;
import io.github.dailystruggle.rtp.common.importer.AbstractForeignConfigImporter.DiscoveredWorldRegion;
import io.github.dailystruggle.rtp.common.importer.AbstractForeignConfigImporter.GlobalSettings;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("AbstractForeignConfigImporter branch coverage")
class AbstractForeignConfigImporterCoverageTest {

    static final class Probe extends AbstractForeignConfigImporter {
        @Override
        public String sourceName() {
            return "probe";
        }

        @Override
        public boolean canImport(Path sourcePluginDir) {
            return false;
        }

        @Override
        public ImportResult importConfiguration(Path sourcePluginDir, Path destinationDir, boolean overwrite) {
            return ImportResult.success(sourceName(), List.of(), List.of(), List.of());
        }
    }

    private final Probe importer = new Probe();

    // Blocks file creation at `p` by occupying it with a non-empty directory.
    private static void occupyWithDirectory(Path p) throws IOException {
        Files.createDirectories(p);
        Files.writeString(p.resolve("blocker.txt"), "x");
    }

    @Test
    @DisplayName("findValueFuzzy guards and lookup tiers")
    void findValueFuzzyTiers() {
        assertNull(importer.findValueFuzzy(null, "a"));
        RtpYamlConfig yaml = RtpYamlConfig.parse("""
                FooBar: 1
                Max_Radius: 2
                cooldown: 3
                nested:
                  inner: 4
                """);
        assertNull(importer.findValueFuzzy(yaml, (String[]) null));
        assertNull(importer.findValueFuzzy(yaml));

        assertEquals(1, importer.findValueFuzzy(yaml, "foobar"));
        assertEquals(2, importer.findValueFuzzy(yaml, "---", "max-radius"));
        assertEquals(3, importer.findValueFuzzy(yaml, "cooldwon"));
        assertNull(importer.findValueFuzzy(yaml, "--", "zz"));
        assertInstanceOf(RtpYamlSection.class, importer.findValueFuzzy(yaml, "NESTED"));
        assertNull(importer.findValueFuzzy(yaml, "completelyunrelatedkey"));
    }

    @Test
    @DisplayName("getSectionCaseInsensitive config and varargs variants")
    void sectionLookups() {
        RtpYamlConfig yaml = RtpYamlConfig.parse("""
                Nested_Sec:
                  a: 1
                Scalar: 5
                """);
        assertNull(importer.getSectionCaseInsensitive((RtpYamlConfig) null, "x"));
        assertNull(importer.getSectionCaseInsensitive(yaml, (String) null));
        assertNotNull(importer.getSectionCaseInsensitive(yaml, "Nested_Sec"));
        assertNotNull(importer.getSectionCaseInsensitive(yaml, "nested_sec"));
        assertNotNull(importer.getSectionCaseInsensitive(yaml, "nested-sec"));
        assertNull(importer.getSectionCaseInsensitive(yaml, "scalar"));
        assertNull(importer.getSectionCaseInsensitive(yaml, "--"));
        assertNull(importer.getSectionCaseInsensitive(yaml, "missing"));

        RtpYamlSection parent = yaml;
        assertNull(importer.getSectionCaseInsensitive((RtpYamlSection) null, "x"));
        assertNull(importer.getSectionCaseInsensitive(parent, (String[]) null));
        assertNotNull(importer.getSectionCaseInsensitive(parent, "NESTED_SEC"));
        assertNotNull(importer.getSectionCaseInsensitive(parent, "--", "nested.sec"));
        assertNull(importer.getSectionCaseInsensitive(parent, "SCALAR", "none"));
    }

    @Test
    @DisplayName("containsCaseInsensitive and typed getter fallbacks")
    void typedGetterFallbacks() {
        RtpYamlConfig yaml = RtpYamlConfig.parse("""
                Key: 1
                list:
                  - first
                  - second
                empty_list: []
                bad_num: "abc"
                no_val: "no"
                false_val: "false"
                maybe: "maybe"
                """);
        assertFalse(importer.containsCaseInsensitive(null, "Key"));
        assertTrue(importer.containsCaseInsensitive(yaml, "Key"));
        assertTrue(importer.containsCaseInsensitive(yaml, "KEY"));
        assertFalse(importer.containsCaseInsensitive(yaml, "absent"));

        assertEquals("d", importer.getStringCaseInsensitive(null, "d", "Key"));
        assertEquals("first", importer.getStringCaseInsensitive(yaml, "d", "list"));
        assertEquals(0, importer.getIntCaseInsensitive(null, 0, "Key"));
        assertEquals(7, importer.getIntCaseInsensitive(yaml, 7, "bad_num"));
        assertEquals(7L, importer.getLongCaseInsensitive(null, 7L, "Key"));
        assertEquals(7L, importer.getLongCaseInsensitive(yaml, 7L, "bad_num"));
        assertEquals(1.5, importer.getDoubleCaseInsensitive(null, 1.5, "Key"));
        assertEquals(1.5, importer.getDoubleCaseInsensitive(yaml, 1.5, "bad_num"));
        assertTrue(importer.getBooleanCaseInsensitive(null, true, "Key"));
        assertFalse(importer.getBooleanCaseInsensitive(yaml, true, "no_val"));
        assertFalse(importer.getBooleanCaseInsensitive(yaml, true, "false_val"));
        assertTrue(importer.getBooleanCaseInsensitive(yaml, true, "maybe"));
        assertTrue(importer.getStringListCaseInsensitive(null, "list").isEmpty());
        assertEquals(List.of("first", "second"), importer.getStringListCaseInsensitive(yaml, "list"));

        RtpYamlConfig withEmpty = new RtpYamlConfig();
        withEmpty.set("items", new ArrayList<>());
        assertEquals("d", importer.getStringCaseInsensitive(withEmpty, "d", "items"));
    }

    @Test
    @DisplayName("writeConfigFile reports IO failure as warning")
    void writeConfigFileFailure(@TempDir Path tmp) throws IOException {
        Path blocker = tmp.resolve("blocker");
        Files.writeString(blocker, "file");
        List<String> warnings = new ArrayList<>();
        List<Path> created = new ArrayList<>();
        assertFalse(importer.writeConfigFile(blocker.resolve("child.yml"), "a: 1", true,
                created, new ArrayList<>(), warnings));
        assertTrue(created.isEmpty());
        assertTrue(warnings.get(0).startsWith("Failed to write"));
    }

    @Test
    @DisplayName("createRegionYaml defaults and normal-distribution parameters")
    void createRegionYamlVariants() {
        RtpYamlConfig noShape = importer.createRegionYaml("r", "w", null, 1, 2, 3, 4, 0.0);
        assertEquals("CIRCLE", noShape.getString("shape.name"));
        assertFalse(noShape.contains("biomes"));
        RtpYamlConfig normal = importer.createRegionYaml("r", "w", "CIRCLE_NORMAL", 1, 2, 3, 4, 0.0, List.of());
        assertEquals(0.5, normal.getDouble("shape.mean"), 1e-9);
        assertFalse(normal.contains("biomes"));
    }

    @Test
    @DisplayName("mapShape normalizes null, square, and rectangle")
    void mapShapeVariants() {
        assertEquals("CIRCLE", importer.mapShape(null));
        assertEquals("SQUARE", importer.mapShape(" square "));
        assertEquals("SQUARE", importer.mapShape("rectangle"));
        assertEquals("CIRCLE", importer.mapShape("round"));
    }

    @Test
    @DisplayName("mirrorDatabaseConfig maps driver families and honours existing files")
    void mirrorDatabaseVariants(@TempDir Path tmp) throws IOException {
        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        importer.mirrorDatabaseConfig(RtpYamlConfig.parse("other: 1\n"), tmp, true, mapped, written, warnings);
        assertTrue(mapped.isEmpty());

        Path pg = tmp.resolve("pg");
        Files.createDirectories(pg);
        Files.writeString(pg.resolve("database.yml"), "old: true\n");
        importer.mirrorDatabaseConfig(RtpYamlConfig.parse("""
                database:
                  type: PostgreSQL
                """), pg, true, mapped, written, warnings);
        assertTrue(Files.readString(pg.resolve("advanced/database.yml")).contains("postgresql"));
        assertTrue(Files.readString(pg.resolve("database.yml")).contains("postgresql"));
        assertEquals(2, mapped.size());

        importer.mirrorDatabaseConfig(RtpYamlConfig.parse("""
                sql:
                  driver: h2
                """), pg, false, mapped, written, warnings);
        assertEquals(2, warnings.stream().filter(w -> w.startsWith("Skipping database config")).count());

        Path sqlite = tmp.resolve("sqlite");
        Files.createDirectories(sqlite);
        Files.writeString(sqlite.resolve("advanced"), "not a dir");
        mapped.clear();
        importer.mirrorDatabaseConfig(RtpYamlConfig.parse("""
                mysql:
                  type: Flatfile
                """), sqlite, true, mapped, written, warnings);
        assertTrue(mapped.isEmpty());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to write database config")));
    }

    @Test
    @DisplayName("mirrorEffectsConfig section-level tokens, scaling, and effects.yml update")
    void mirrorEffectsSections(@TempDir Path tmp) throws IOException {
        Files.writeString(tmp.resolve("effects.yml"), "teleport: []\nversion: 1.0\n");
        RtpYamlConfig src = RtpYamlConfig.parse("""
                Effects:
                  Sounds:
                    Sound: "block_note_block_pling"
                    Volume: 50
                    Pitch: 0
                  Title:
                    Enabled: false
                  ActionBar:
                    Message: ""
                  Potions:
                    list:
                      - "speed:10:2"
                      - "jump"
                      - "regen 40"
                      - "haste:abc:xyz"
                  Particles:
                    Enabled: false
                Settings:
                  Invulnerable: 3
                """);
        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        importer.mirrorEffectsConfig(src, tmp, true, mapped, written, warnings);

        Path profile = tmp.resolve("definitions/effects/imported_probe_teleport.yml");
        String content = Files.readString(profile);
        assertTrue(content.contains("SOUND.BLOCK_NOTE_BLOCK_PLING.50.100.0.0.0"), content);
        assertTrue(content.contains("POTION.SPEED.200.2"), content);
        assertTrue(content.contains("POTION.JUMP.100.1"), content);
        assertTrue(content.contains("POTION.REGEN.40.1"), content);
        assertTrue(content.contains("POTION.HASTE.100.1"), content);
        assertTrue(content.contains("POTION.RESISTANCE.60.1"), content);
        assertFalse(content.contains("TITLE."), content);
        assertFalse(content.contains("PARTICLE."), content);
        assertTrue(Files.readString(tmp.resolve("effects.yml")).contains("SOUND."));
        assertTrue(mapped.stream().anyMatch(m -> m.startsWith("Effects: imported_probe_teleport")));
    }

    @Test
    @DisplayName("mirrorEffectsConfig root-level sections and disabled entries")
    void mirrorEffectsRootLevel(@TempDir Path tmp) throws IOException {
        RtpYamlConfig src = RtpYamlConfig.parse("""
                Sounds:
                  Enabled: false
                Title:
                  Title: ""
                  Subtitle: ""
                ActionBar:
                  Enabled: false
                Potions:
                  Enabled: false
                Particles:
                  Type: flame
                  Amount: 12
                Invulnerable: 2
                Settings:
                  Invulnerable: 9
                """);
        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        importer.mirrorEffectsConfig(src, tmp, false, mapped, written, warnings);
        String content = Files.readString(tmp.resolve("definitions/effects/imported_probe_teleport.yml"));
        assertTrue(content.contains("PARTICLE.FLAME.12"), content);
        assertTrue(content.contains("POTION.RESISTANCE.40.1"), content);
        assertFalse(content.contains("SOUND."), content);

        // Existing profile without overwrite is left untouched.
        mapped.clear();
        importer.mirrorEffectsConfig(src, tmp, false, mapped, written, warnings);
        assertTrue(mapped.isEmpty());
    }

    @Test
    @DisplayName("mirrorEffectsConfig potions list under effects and no-token early exit")
    void mirrorEffectsPotionList(@TempDir Path tmp) throws IOException {
        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        importer.mirrorEffectsConfig(RtpYamlConfig.parse("unrelated: 1\n"), tmp, true, mapped, written, warnings);
        assertFalse(Files.exists(tmp.resolve("definitions")));

        RtpYamlConfig src = RtpYamlConfig.parse("""
                effects:
                  potions:
                    - "SPEED:5:1"
                    - "NIGHT_VISION"
                    - "SLOW 60 x"
                """);
        importer.mirrorEffectsConfig(src, tmp, true, mapped, written, warnings);
        String content = Files.readString(tmp.resolve("definitions/effects/imported_probe_teleport.yml"));
        assertTrue(content.contains("POTION.SPEED.100.1"), content);
        assertTrue(content.contains("POTION.NIGHT_VISION.100.1"), content);
        assertTrue(content.contains("POTION.SLOW.60.1"), content);
    }

    @Test
    @DisplayName("mirrorEffectsConfig reports profile and effects.yml IO failures")
    void mirrorEffectsFailures(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("definitions"));
        Files.writeString(tmp.resolve("definitions/effects"), "not a dir");
        occupyWithDirectory(tmp.resolve("effects.yml"));
        RtpYamlConfig src = RtpYamlConfig.parse("""
                Sounds:
                  Volume: 0.5
                  Pitch: 3
                ActionBar:
                  Message: "hi"
                Title:
                  Title: "T"
                """);
        List<String> mapped = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        importer.mirrorEffectsConfig(src, tmp, true, mapped, new ArrayList<>(), warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to write effect profile")), warnings.toString());
        // File.length() of a directory is 0 on Windows (load yields an empty config, the write fails)
        // but non-zero on POSIX (the read fails), so either warning reports the effects.yml failure.
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to update effects.yml")
                || w.startsWith("Failed to write effects.yml")), warnings.toString());
        assertTrue(mapped.isEmpty());
    }

    @Test
    @DisplayName("discoverWorldSections multi-file directory topology")
    void discoverDirectoryTopology(@TempDir Path tmp) throws IOException {
        Path worlds = tmp.resolve("worlds");
        Files.createDirectories(worlds);
        Files.writeString(worlds.resolve("alpha.yml"), "max-radius: 100\n");
        Files.writeString(worlds.resolve("beta.yaml"), "max-radius: 200\n");
        Files.writeString(worlds.resolve("ignored.txt"), "x");
        Map<String, RtpYamlSection> found = importer.discoverWorldSections(tmp, null, "worlds", "worlds");
        assertEquals(2, found.size());
        assertTrue(found.containsKey("alpha"));
        assertTrue(found.containsKey("beta"));

        assertTrue(importer.discoverWorldSections(tmp, null, "missing").isEmpty());
        assertTrue(importer.discoverWorldSections(tmp, null, "").isEmpty());
        assertTrue(importer.discoverWorldSections(null, null, "worlds").isEmpty());
    }

    @Test
    @DisplayName("discoverWorldSections keyed mapping, sequence, and flat topologies")
    void discoverInlineTopologies() {
        RtpYamlConfig keyed = RtpYamlConfig.parse("""
                Worlds:
                  world:
                    max: 500
                  nether:
                    max: 300
                  stray: 5
                """);
        Map<String, RtpYamlSection> k = importer.discoverWorldSections(null, keyed, null, "absent", "worlds");
        assertEquals(List.of("world", "nether"), new ArrayList<>(k.keySet()));

        RtpYamlConfig seq = RtpYamlConfig.parse("""
                zones:
                  - world_a:
                      max: 10
                  - name: world_b
                    max: 20
                  - single: scalar
                  - plain
                """);
        Map<String, RtpYamlSection> s = importer.discoverWorldSections(null, seq, null, "zones");
        assertTrue(s.containsKey("world_a"));
        assertEquals("world_a", s.get("world_a").getString("world"));
        assertTrue(s.containsKey("world_b"));
        assertTrue(s.containsKey("world"));

        RtpYamlConfig flat = RtpYamlConfig.parse("""
                world: overworld
                radius: 1000
                """);
        Map<String, RtpYamlSection> f = importer.discoverWorldSections(null, flat, null, (String[]) null);
        assertEquals(List.of("overworld"), new ArrayList<>(f.keySet()));
        assertEquals(1, importer.discoverWorldSections(null, flat, null).size());

        assertTrue(importer.discoverWorldSections(null, RtpYamlConfig.parse("a: 1\n"), null, "worlds").isEmpty());
    }

    @Test
    @DisplayName("extractWorldRegion inherits defaults and blank world falls back to target")
    void extractWithDefaults() {
        DiscoveredWorldRegion defaults = new DiscoveredWorldRegion("d", "def_world", "SQUARE", 10, 900,
                5, 6, 30, 200, 7.5, 42, List.of("ocean"));
        RtpYamlConfig sec = RtpYamlConfig.parse("""
                world: ""
                gaussian:
                  enabled: true
                """);
        DiscoveredWorldRegion r = importer.extractWorldRegion("target", sec, defaults);
        assertEquals("target", r.world());
        assertEquals(10, r.minRadius());
        assertEquals(900, r.maxRadius());
        assertEquals(5, r.centerX());
        assertEquals(6, r.centerZ());
        assertEquals(30, r.minY());
        assertEquals(200, r.maxY());
        assertEquals(7.5, r.price(), 1e-9);
        assertEquals(42, r.cacheCap());
        assertEquals("SQUARE_NORMAL", r.shape());
        assertEquals(List.of("ocean"), r.biomes());

        DiscoveredWorldRegion zeroPrice = new DiscoveredWorldRegion("d", "w", "CIRCLE", 1, 2, 0, 0, 0.0);
        DiscoveredWorldRegion r2 = importer.extractWorldRegion("t", RtpYamlConfig.parse("a: 1\n"), zeroPrice);
        assertEquals(0.0, r2.price(), 1e-9);
        assertTrue(r2.biomes() == null || r2.biomes().isEmpty(), String.valueOf(r2.biomes()));
        assertEquals(-1, r2.cacheCap());
    }

    @Test
    @DisplayName("extractWorldRegion free and paid sub-sections")
    void extractFreePaid() {
        RtpYamlConfig both = RtpYamlConfig.parse("""
                free:
                  min-radius: 11
                  max-radius: 111
                paid:
                  cost: 25
                  min-radius: 99
                """);
        DiscoveredWorldRegion r = importer.extractWorldRegion("w", both, null);
        assertEquals(11, r.minRadius());
        assertEquals(111, r.maxRadius());
        assertEquals(25.0, r.price(), 1e-9);

        RtpYamlConfig paidOnly = RtpYamlConfig.parse("""
                paid:
                  price: 5
                  min-distance: 33
                  max-distance: 333
                """);
        DiscoveredWorldRegion p = importer.extractWorldRegion("w", paidOnly, null);
        assertEquals(33, p.minRadius());
        assertEquals(333, p.maxRadius());
        assertEquals(5.0, p.price(), 1e-9);
    }

    @Test
    @DisplayName("resolveSafeChild rejects unsafe names")
    void resolveSafeChildRejections(@TempDir Path tmp) {
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(null, "a.yml"));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, null));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, ""));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, "a".repeat(161)));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, ".hidden"));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, "a\u0001b"));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, "a\u007fb"));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, "a/b"));
        assertNull(AbstractForeignConfigImporter.resolveSafeChild(tmp, "c:x"));
        assertEquals(tmp.toAbsolutePath().normalize().resolve("ok.yml"),
                AbstractForeignConfigImporter.resolveSafeChild(tmp, "ok.yml"));
    }

    @Test
    @DisplayName("emitRegionsAndWorlds naming, vertical bounds, cache cap, and additional worlds")
    void emitRegionsHappyPath(@TempDir Path tmp) throws IOException {
        Files.createDirectories(tmp.resolve("definitions/worlds"));
        Files.writeString(tmp.resolve("definitions/worlds/kept.yml"), "keep: true\n");
        List<DiscoveredWorldRegion> regions = List.of(
                new DiscoveredWorldRegion("my-world", "my_world", "CIRCLE", 1, 2, 0, 0, 0, 100, 0.0, 8,
                        null, java.util.Arrays.asList(null, " ", "my_world", "wild*", "q?", "bad/name", "kept", "extra")),
                new DiscoveredWorldRegion("pre_region", "w2", "CIRCLE", 1, 2, 0, 0, 64, 400, 0.0, -1, null, null),
                new DiscoveredWorldRegion("justrtp_loc_a", "w3", "CIRCLE", 1, 2, 0, 0, 0.0),
                new DiscoveredWorldRegion("x_loc_b", "w4", "CIRCLE", 1, 2, 0, 0, 0.0),
                new DiscoveredWorldRegion("unsafe", "../evil", "CIRCLE", 1, 2, 0, 0, 0.0));
        List<Path> written = new ArrayList<>();
        List<String> mapped = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        importer.emitRegionsAndWorlds(regions, tmp, false, written, mapped, errors);

        RtpYamlConfig region = RtpYamlConfig.load(tmp.resolve("definitions/regions/my_world_region.yml").toFile());
        assertEquals(0, region.getInt("vert.minY"));
        assertEquals(100, region.getInt("vert.maxY"));
        assertEquals(8, region.getInt("cacheCap"));
        assertEquals(400, RtpYamlConfig.load(tmp.resolve("definitions/regions/pre_region.yml").toFile()).getInt("vert.maxY"));
        assertTrue(Files.exists(tmp.resolve("definitions/regions/justrtp_loc_a.yml")));
        assertTrue(Files.exists(tmp.resolve("definitions/regions/x_loc_b.yml")));
        assertTrue(Files.exists(tmp.resolve("definitions/worlds/extra.yml")));
        assertEquals("keep: true\n", Files.readString(tmp.resolve("definitions/worlds/kept.yml")));
        assertTrue(errors.stream().anyMatch(e -> e.contains("unsafe file name: bad/name")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.contains("region=unsafe_region")), errors.toString());

        // Re-run without overwrite: existing region file and existing world file are reported.
        errors.clear();
        Files.delete(tmp.resolve("definitions/regions/justrtp_loc_a.yml"));
        importer.emitRegionsAndWorlds(regions.subList(0, 3), tmp, false, written, mapped, errors);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Region file already exists")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("World file already exists")), errors.toString());
    }

    @Test
    @DisplayName("emitRegionsAndWorlds reports directory and file write failures")
    void emitRegionsFailures(@TempDir Path tmp) throws IOException {
        Path fileDest = tmp.resolve("filedest");
        Files.writeString(fileDest, "x");
        List<String> errors = new ArrayList<>();
        importer.emitRegionsAndWorlds(List.of(), fileDest, true, new ArrayList<>(), new ArrayList<>(), errors);
        assertTrue(errors.get(0).startsWith("Failed to create destination directories"));

        Path dest = tmp.resolve("dest");
        occupyWithDirectory(dest.resolve("definitions/regions/r_region.yml"));
        occupyWithDirectory(dest.resolve("definitions/worlds/w.yml"));
        occupyWithDirectory(dest.resolve("definitions/worlds/extra.yml"));
        errors.clear();
        DiscoveredWorldRegion r = new DiscoveredWorldRegion("r", "w", "CIRCLE", 1, 2, 0, 0, 64, 320, 0.0, -1,
                null, List.of("extra"));
        importer.emitRegionsAndWorlds(List.of(r), dest, true, new ArrayList<>(), new ArrayList<>(), errors);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Failed to write region file")), errors.toString());
        assertEquals(2, errors.stream().filter(e -> e.startsWith("Failed to write world file")).count(), errors.toString());
    }

    @Test
    @DisplayName("loadDirectoryConfigs handles null, non-directory, and null warnings")
    void loadDirectoryConfigsVariants(@TempDir Path tmp) throws IOException {
        assertTrue(importer.loadDirectoryConfigs(null, new ArrayList<>()).isEmpty());
        Path file = tmp.resolve("plain.txt");
        Files.writeString(file, "x");
        assertTrue(importer.loadDirectoryConfigs(file, new ArrayList<>()).isEmpty());

        Path dir = tmp.resolve("cfgs");
        Files.createDirectories(dir);
        Files.writeString(dir.resolve("Alpha.yml"), "a: 1\n");
        Files.writeString(dir.resolve("beta.yaml"), "b: 2\n");
        Map<String, RtpYamlConfig> loaded = importer.loadDirectoryConfigs(dir, null);
        assertEquals(2, loaded.size());
        assertEquals(1, loaded.get("alpha").getInt("a"));
        assertEquals(2, importer.loadDirectoryConfigs(dir, new ArrayList<>()).size());
    }

    @Test
    @DisplayName("updateDestinationConfig writes each global flag independently")
    void updateDestinationConfigFlags(@TempDir Path tmp) throws IOException {
        List<String> warnings = new ArrayList<>();
        importer.updateDestinationConfig(tmp, null, new ArrayList<>(), new ArrayList<>(), warnings);
        importer.updateDestinationConfig(tmp, GlobalSettings.builder().build(), new ArrayList<>(), new ArrayList<>(), warnings);
        assertFalse(Files.exists(tmp.resolve("config.yml")));
        assertFalse(Files.exists(tmp.resolve("performance.yml")));

        List<GlobalSettings> singles = List.of(
                GlobalSettings.builder().delay(4).build(),
                GlobalSettings.builder().lockAfter(2).build(),
                GlobalSettings.builder().setAsRespawn(true).build(),
                GlobalSettings.builder().cancelOnMove(true).build(),
                GlobalSettings.builder().rtpOnFirstJoin(true).build(),
                GlobalSettings.builder().rtpOnDeath(true).build(),
                GlobalSettings.builder().maxAttempts(5).build(),
                GlobalSettings.builder().cacheCap(9).build());
        List<String> mapped = new ArrayList<>();
        for (GlobalSettings s : singles) {
            importer.updateDestinationConfig(tmp, s, mapped, new ArrayList<>(), warnings);
        }
        RtpYamlConfig cfg = RtpYamlConfig.load(tmp.resolve("config.yml").toFile());
        assertEquals(4, cfg.getInt("teleportDelay"));
        assertEquals(2, cfg.getInt("lockAfterUses"));
        assertTrue(cfg.getBoolean("setRespawnOnTeleport"));
        assertTrue(cfg.getBoolean("cancelOnMove"));
        assertTrue(cfg.getBoolean("rtpOnFirstJoin"));
        assertTrue(cfg.getBoolean("rtpOnDeath"));
        assertEquals(5, cfg.getInt("maxAttempts"));
        assertEquals(9, cfg.getInt("cacheCap"));
        assertFalse(Files.exists(tmp.resolve("performance.yml")));

        importer.updateDestinationConfig(tmp, GlobalSettings.builder().queueTargetSize(12).build(),
                mapped, new ArrayList<>(), warnings);
        importer.updateDestinationConfig(tmp, GlobalSettings.builder().maxAttempts(50).build(),
                mapped, new ArrayList<>(), warnings);
        RtpYamlConfig perf = RtpYamlConfig.load(tmp.resolve("performance.yml").toFile());
        assertEquals(12, perf.getInt("queue.targetSize"));
        assertEquals(50, perf.getInt("maxAttempts"));
        assertTrue(mapped.contains("Performance: queue.targetSize=12"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("updateDestinationConfig reports config.yml and performance.yml failures")
    void updateDestinationConfigFailures(@TempDir Path tmp) throws IOException {
        occupyWithDirectory(tmp.resolve("config.yml"));
        occupyWithDirectory(tmp.resolve("performance.yml"));
        List<String> warnings = new ArrayList<>();
        importer.updateDestinationConfig(tmp, GlobalSettings.builder().cooldown(1).maxAttempts(20).build(),
                new ArrayList<>(), new ArrayList<>(), warnings);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to update config.yml")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to update performance.yml")), warnings.toString());
    }

    @Test
    @DisplayName("pruneOldBackups keeps the newest five backups")
    void pruneOldBackupsRetention(@TempDir Path tmp) throws IOException {
        importer.pruneOldBackups(null, "x");
        importer.pruneOldBackups(tmp.resolve("missing"), "x");
        for (int i = 0; i < 8; i++) {
            Path b = tmp.resolve("cfg.yml.bak." + i);
            Files.writeString(b, "b" + i);
            Files.setLastModifiedTime(b, FileTime.fromMillis(1_000_000L + i * 1000L));
        }
        Files.writeString(tmp.resolve("other.yml.bak.1"), "o");
        importer.pruneOldBackups(tmp, "cfg.yml");
        for (int i = 0; i < 3; i++) {
            assertFalse(Files.exists(tmp.resolve("cfg.yml.bak." + i)));
        }
        for (int i = 3; i < 8; i++) {
            assertTrue(Files.exists(tmp.resolve("cfg.yml.bak." + i)));
        }
        assertTrue(Files.exists(tmp.resolve("other.yml.bak.1")));
    }

    @Test
    @DisplayName("mirrorZonesConfig guards for missing, unreadable, and empty sources")
    void mirrorZonesGuards(@TempDir Path tmp) throws IOException {
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        Path dest = tmp.resolve("dest");
        importer.mirrorZonesConfig(null, dest, true, new ArrayList<>(), written, warnings, errors);
        importer.mirrorZonesConfig(tmp.resolve("nosrc"), dest, true, new ArrayList<>(), written, warnings, errors);

        Path unreadable = tmp.resolve("unreadable");
        occupyWithDirectory(unreadable.resolve("rtp_zones.yml"));
        importer.mirrorZonesConfig(unreadable, dest, true, new ArrayList<>(), written, warnings, errors);
        assertTrue(warnings.stream().anyMatch(w -> w.startsWith("Failed to parse")), warnings.toString());

        Path empty = tmp.resolve("empty");
        Files.createDirectories(empty);
        Files.writeString(empty.resolve("rtp_zones.yml"), "");
        importer.mirrorZonesConfig(empty, dest, true, new ArrayList<>(), written, warnings, errors);
        assertTrue(written.isEmpty());
        assertTrue(errors.isEmpty());
    }

    @Test
    @DisplayName("mirrorZonesConfig root-level zones, bounds variants, and trigger types")
    void mirrorZonesRootLevel(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("rtp_zones.yml"), """
                version: 1
                scalar_zone: 5
                portal_zone:
                  world: lobby
                  type: nether_portal
                  pos1:
                    x: 10
                    y: 70
                    z: -5
                  pos2:
                    x: -10
                    y: 60
                    z: 5
                  interval: 30
                plate_zone:
                  type: pressure_plate
                  pos1:
                    x: 1
                  x1: 4
                  y1: 64
                  z1: 8
                  x2: 2
                  y2: 66
                  z2: 3
                walk_zone:
                  type: walk
                """);
        Path dest = tmp.resolve("dest");
        List<String> mapped = new ArrayList<>();
        List<Path> written = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        List<String> errors = new ArrayList<>();
        importer.mirrorZonesConfig(src, dest, false, mapped, written, warnings, errors);
        assertEquals(3, written.size(), errors.toString());

        RtpYamlConfig portal = RtpYamlConfig.load(dest.resolve("definitions/actions/zone_portal_zone.yml").toFile());
        assertEquals("PORTAL", portal.getString("triggers.zone_portal_zone_trigger.type"));
        assertEquals(-10, portal.getInt("triggers.zone_portal_zone_trigger.pos1.x"));
        assertEquals(70, portal.getInt("triggers.zone_portal_zone_trigger.pos2.y"));
        assertEquals("30s", portal.getString("triggers.zone_portal_zone_trigger.batchInterval"));

        RtpYamlConfig plate = RtpYamlConfig.load(dest.resolve("definitions/actions/zone_plate_zone.yml").toFile());
        assertEquals("PRESSURE_PLATE", plate.getString("triggers.zone_plate_zone_trigger.type"));
        assertEquals(2, plate.getInt("triggers.zone_plate_zone_trigger.pos1.x"));
        assertEquals(8, plate.getInt("triggers.zone_plate_zone_trigger.pos2.z"));
        assertFalse(plate.contains("triggers.zone_plate_zone_trigger.batchInterval"));

        RtpYamlConfig walk = RtpYamlConfig.load(dest.resolve("definitions/actions/zone_walk_zone.yml").toFile());
        assertEquals("STEP_IN", walk.getString("triggers.zone_walk_zone_trigger.type"));

        importer.mirrorZonesConfig(src, dest, false, mapped, written, warnings, errors);
        assertEquals(3, warnings.stream().filter(w -> w.startsWith("Skipping zone action")).count(), warnings.toString());
    }

    @Test
    @DisplayName("mirrorZonesConfig nested zones section, unsafe ids, and write failure")
    void mirrorZonesFailures(@TempDir Path tmp) throws IOException {
        Path src = tmp.resolve("src");
        Files.createDirectories(src);
        Files.writeString(src.resolve("rtp_zones.yml"), """
                zones:
                  "bad:zone":
                    world: w
                  good:
                    world: w
                """);
        Path dest = tmp.resolve("dest");
        Files.createDirectories(dest.resolve("definitions"));
        Files.writeString(dest.resolve("definitions/actions"), "not a dir");
        List<String> errors = new ArrayList<>();
        importer.mirrorZonesConfig(src, dest, true, new ArrayList<>(), new ArrayList<>(), new ArrayList<>(), errors);
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Skipping zone with unsafe file name")), errors.toString());
        assertTrue(errors.stream().anyMatch(e -> e.startsWith("Failed to write zone action")), errors.toString());
    }
}
