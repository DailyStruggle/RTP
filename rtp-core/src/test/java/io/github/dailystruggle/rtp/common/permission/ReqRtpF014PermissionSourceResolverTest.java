package io.github.dailystruggle.rtp.common.permission;

import io.github.dailystruggle.rtp.api.server.RTPServerAccessor;
import io.github.dailystruggle.rtp.common.RTP;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

@DisplayName("REQ-RTP-F-014 - permission migration derives sources from plugin folders and withholds broad targets")
class ReqRtpF014PermissionSourceResolverTest {

    @TempDir
    Path plugins;

    private PermissionMigrationService service;
    private io.github.dailystruggle.rtp.common.configuration.Configs savedConfigs;
    private RTPServerAccessor savedAccessor;

    @BeforeEach
    void setUp() throws IOException {
        savedConfigs = RTP.configs;
        savedAccessor = RTP.serverAccessor;
        RTP.configs = null;
        RTP.serverAccessor = null;
        service = new PermissionMigrationService();

        write("BetterRTP/config.yml", """
                Default:
                  MaxRadius: 1000
                  MinRadius: 10
                  CenterX: 0
                  CenterZ: 0
                Settings:
                  Cooldown:
                    Enabled: true
                    Time: 600
                """);
        // Unlisted rtp plugin: only the generic shape of its config identifies it.
        write("FooRTP/settings.yml", """
                worlds:
                  world:
                    raduis: 5000
                teleport-delay: 3
                """);
        // WorldEdit: has a config.yml (so the importer's folder probe accepts it) but only brush radii.
        write("WorldEdit/config.yml", """
                limits:
                  max-blocks-changed:
                    default: -1
                  max-polygonal-points:
                    default: -1
                  max-radius: -1
                  max-super-pickaxe-size: 5
                  max-brush-radius: 6
                  butcher-radius:
                    default: -1
                use-inventory:
                  enable: false
                logging:
                  log-commands: false
                navigation-wand:
                  item: minecraft:compass
                  max-distance: 100
                scripting:
                  timeout: 3000
                saving:
                  dir: schematics
                history:
                  size: 15
                """);
        // Stock EssentialsX keys: cooldown, delay, near/chat radius and teleport-to-center hit every concept.
        write("Essentials/config.yml", """
                teleport-safety: true
                teleport-to-center: true
                teleport-cooldown: 0
                teleport-delay: 0
                teleport-invulnerability: 4
                heal-cooldown: 60
                near-radius: 200
                world-teleport-permissions: false
                chat:
                  radius: 0
                """);
        // Homes/warps plugin: cooldown and delay alone are not an rtp signal.
        write("HomesPlus/config.yml", """
                settings:
                  teleport-cooldown: 30
                  teleport-delay: 3
                  max-homes: 5
                """);
    }

    @AfterEach
    void tearDown() {
        RTP.configs = savedConfigs;
        RTP.serverAccessor = savedAccessor;
    }

    @Test
    @DisplayName("Folders with rtp concepts become sources; a config.yml with only brush radii does not")
    void deriveSourcesFromPluginFolders() {
        Set<String> derived = PermissionSourceResolver.deriveSources(plugins);
        assertTrue(derived.contains("betterrtp"), "BetterRTP must be derived: " + derived);
        assertTrue(derived.contains("foortp"), "an unlisted rtp plugin must be derived: " + derived);
        assertFalse(derived.contains("worldedit"), "WorldEdit must not be derived: " + derived);
        assertFalse(derived.contains("essentials"), "EssentialsX must not be derived: " + derived);
        assertFalse(derived.contains("homesplus"), "cooldown/delay alone must not derive: " + derived);
        assertTrue(PermissionSourceResolver.deriveSources(null).isEmpty());
    }

    @Test
    @DisplayName("Cooldown and delay without a radius concept never qualify a folder")
    void radiusConceptRequired() {
        Path homes = plugins.resolve("HomesPlus");
        assertEquals(Set.of("cooldown", "delay"), PermissionSourceResolver.conceptHits(homes));
        assertFalse(PermissionSourceResolver.isRtpLikeFolder(homes));
        assertTrue(PermissionSourceResolver.isRtpLikeFolder(plugins.resolve("FooRTP")));
        // Essentials' keys alone would qualify; the suite exclusion is what keeps it out.
        assertTrue(PermissionSourceResolver.isRtpLikeFolder(plugins.resolve("Essentials")));
    }

    @Test
    @DisplayName("Essentials per-world grants never map to RTP world permissions without source=")
    void essentialsWorldGrantsNotInferred() {
        Set<String> derived = PermissionSourceResolver.deriveSources(plugins);
        List<PermissionMigrationService.ParsedNode> nodes = List.of(
                node("essentials.world"),
                node("essentials.worlds.survival"),
                node("essentials.nocooldown"));

        PermissionMigrationService.MigrationPlan plan =
                service.planMigration("group", "default", nodes, null, derived, false);
        assertTrue(targets(plan).isEmpty(), targets(plan).toString());

        // Even a derived source whose name contains the suite name must not pull its nodes in.
        PermissionMigrationService.MigrationPlan lookalike =
                service.planMigration("group", "default", nodes, null, Set.of("essentials", "essentialsrtp"), false);
        assertTrue(targets(lookalike).isEmpty(), targets(lookalike).toString());
        assertFalse(PermissionSourceResolver.matchesSource("cmi.command.rtp", Set.of("cmi")));

        PermissionMigrationService.MigrationPlan namedPlan =
                service.planMigration("group", "default", nodes, "essentials", Set.of(), false);
        assertTrue(targets(namedPlan).contains("rtp.worlds.survival"), "explicit source= still maps: "
                + targets(namedPlan));
    }

    @Test
    @DisplayName("Without source=, only nodes of derived sources reach the generic mapper")
    void unnamedSourceScopesGenericMapper() {
        Set<String> derived = PermissionSourceResolver.deriveSources(plugins);
        List<PermissionMigrationService.ParsedNode> nodes = List.of(
                node("foortp.use"),
                node("betterrtp.world.survival"),
                node("worldedit.*"),
                node("worldedit.use"),
                node("essentials.admin"));

        PermissionMigrationService.MigrationPlan plan =
                service.planMigration("group", "builders", nodes, null, derived, false);

        List<String> targets = targets(plan);
        assertTrue(targets.contains("rtp.use"), "foortp.use must map: " + targets);
        assertTrue(targets.contains("rtp.worlds.survival"), "betterrtp.world.* must map: " + targets);
        assertEquals(2, targets.size(), "no other plugin's node may map: " + targets);
        assertTrue(plan.getSkippedPrivileged().isEmpty(), "unmatched prefixes are ignored, not withheld");
    }

    @Test
    @DisplayName("Broad targets from a derived source are withheld until source= is named")
    void broadTargetsNeedNamedSource() {
        List<PermissionMigrationService.ParsedNode> nodes = List.of(
                node("foortp.*"),
                node("foortp.admin"),
                node("foortp.reload"),
                node("foortp.nocooldown"));

        PermissionMigrationService.MigrationPlan derivedPlan =
                service.planMigration("group", "staff", nodes, null, Set.of("foortp"), false);
        assertEquals(List.of("rtp.noCooldown"), targets(derivedPlan));
        assertEquals(3, derivedPlan.getSkippedPrivileged().size(), derivedPlan.getSkippedPrivileged().toString());

        PermissionMigrationService.MigrationPlan allPlan =
                service.planMigration("group", "staff", nodes, "all", Set.of(), false);
        assertEquals(List.of("rtp.noCooldown"), targets(allPlan), "source=all is not a named source");

        PermissionMigrationService.MigrationPlan namedPlan =
                service.planMigration("group", "staff", nodes, "foortp", Set.of(), false);
        assertTrue(targets(namedPlan).containsAll(List.of("rtp.*", "rtp.admin", "rtp.reload", "rtp.noCooldown")),
                targets(namedPlan).toString());
        assertTrue(namedPlan.getSkippedPrivileged().isEmpty());
    }

    @Test
    @DisplayName("The five-argument overload derives sources from the server plugin folder")
    void fiveArgOverloadDerivesFromPluginsDir() throws IOException {
        Path rtpData = Files.createDirectories(plugins.resolve("RTP"));
        RTP.serverAccessor = new io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor(rtpData.toFile());
        assertEquals(plugins.toAbsolutePath(), PermissionSourceResolver.resolvePluginsDir().toAbsolutePath());

        PermissionMigrationService.MigrationPlan plan = service.planMigration("group", "g",
                List.of(node("foortp.use"), node("worldedit.*")), null, false);
        assertEquals(List.of("rtp.use"), targets(plan));
    }

    @Test
    @DisplayName("Prefix matching is fuzzy and never matches RTP's own nodes")
    void prefixMatching() {
        assertTrue(PermissionSourceResolver.matchesSource("jakesrtp.use", Set.of("jakesrtp12")));
        assertTrue(PermissionSourceResolver.matchesSource("betterrtp.use", Set.of("better-rtp")));
        assertTrue(PermissionSourceResolver.matchesSource("betterrpt.use", Set.of("betterrtp")));
        assertFalse(PermissionSourceResolver.matchesSource("rtp.use", Set.of("betterrtp")));
        assertFalse(PermissionSourceResolver.matchesSource("worldedit.*", Set.of("betterrtp", "foortp")));
        assertFalse(PermissionSourceResolver.matchesSource("foortp.use", Set.of()));
        assertEquals(2, PermissionSourceResolver.levenshtein("raduis", "radius"));
        assertTrue(PermissionSourceResolver.keyMatches("raduis", "radius"));
        assertFalse(PermissionSourceResolver.keyMatches("size", "rad"));
    }

    private void write(String rel, String content) throws IOException {
        Path p = plugins.resolve(rel);
        Files.createDirectories(p.getParent());
        Files.writeString(p, content);
    }

    private static PermissionMigrationService.ParsedNode node(String perm) {
        return new PermissionMigrationService.ParsedNode(perm, true, "");
    }

    private static List<String> targets(PermissionMigrationService.MigrationPlan plan) {
        return plan.getMappedEntries().stream()
                .map(PermissionMigrationService.PermissionEntry::getTargetPermission)
                .toList();
    }
}
