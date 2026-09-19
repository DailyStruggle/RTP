package io.github.dailystruggle.rtp.effects;

import io.github.dailystruggle.effectsapi.common.EffectsGroupKeys;
import io.github.dailystruggle.rtp.api.entity.RTPPlayer;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.configuration.Configs;
import io.github.dailystruggle.rtp.common.configuration.MultiConfigParser;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link EffectsResolver} (effects-api-ADR-005, checklist step 11f).
 *
 * <p>Exercises the full {@code effects/<group>.yml} resolution pipeline against
 * a real {@link MultiConfigParser} backed by per-test {@code @TempDir} YAML
 * fixtures, matching the established
 * {@code MultiConfigParserIsolationTest} idiom.
 *
 * <p>Coverage:
 * <ul>
 *   <li>Parsing: simple {@code effects/} dir round-trips through
 *       {@code MultiConfigParser<EffectsGroupKeys>}.</li>
 *   <li>Gating matrix: permission present/absent, {@code players:} allowlist
 *       hit/miss (UUID and name), {@code inherit:} chain, {@code inherit:}
 *       cycle (must not stack-overflow), missing {@code default} synthesised.</li>
 *   <li>Hot-reload: a fresh {@link MultiConfigParser} swap honours new tokens
 *       on the next {@link EffectsResolver#resolveTokens} call.</li>
 *   <li>Union helper: {@link EffectsResolver#resolveUnioned} merges with
 *       caller-supplied permission nodes.</li>
 * </ul>
 *
 * <p>S-005-clean: no chunk I/O. S-006-clean: missing {@code effects/} parser
 * yields an empty list (no NPE) - verified by the no-parser test.
 */
public class EffectsResolverTest {

    @TempDir
    Path tempDir;

    @BeforeEach
    void setUp() throws IOException {
        RTPTestSetup.install(tempDir.toFile());
        RTP.configs = new Configs(tempDir.toFile());
        // effects/ directory is created on first MultiConfigParser construction below;
        // each test writes its own fixture YAMLs before constructing the parser.
        Files.createDirectories(tempDir.resolve("effects"));
    }

    // ---- helpers ----

    private void writeGroup(String fileName, String body) throws IOException {
        Path p = tempDir.resolve("effects").resolve(fileName);
        Files.writeString(p, body);
    }

    private MultiConfigParser<EffectsGroupKeys> rebuildParser() {
        MultiConfigParser<EffectsGroupKeys> mcp =
                new MultiConfigParser<>(EffectsGroupKeys.class, "effects", "1.0", tempDir.toFile());
        RTP.configs.multiConfigParserMap.put(EffectsGroupKeys.class, mcp);
        return mcp;
    }

    private RTPPlayer player(String name, UUID uuid, Set<String> perms) {
        return new StubPlayer(name, uuid, perms);
    }

    // ---- 1. Parsing: round-trip through MultiConfigParser ----

    @Test
    void parsesSimpleDefaultGroup() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.ENTITY_ENDERMAN_TELEPORT.1.0.1.0\n" +
                "  - PARTICLE.PORTAL.32\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        RTPPlayer p = player("alice", UUID.randomUUID(), Collections.emptySet());
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", p, "rtp.effect.postteleport");

        assertEquals(2, tokens.size(), "default group's two effect tokens should resolve");
        assertTrue(tokens.get(0).startsWith("rtp.effect.postteleport."),
                "tokens must be prefixed for EffectFactory.buildEffects");
        assertTrue(tokens.contains("rtp.effect.postteleport.SOUND.ENTITY_ENDERMAN_TELEPORT.1.0.1.0"));
        assertTrue(tokens.contains("rtp.effect.postteleport.PARTICLE.PORTAL.32"));
    }

    // ---- 2. Default fallback when no non-default groups match ----

    @Test
    void fallsBackToDefaultWhenNoOtherGroupMatches() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.LEVELUP.1.0.1.0\n" +
                "version: \"1.0\"\n");
        // A non-default group that gates on a permission alice doesn't have.
        writeGroup("vip.yml",
                "when: postteleport\n" +
                "permission: rtp.vip\n" +
                "effects:\n" +
                "  - SOUND.WITHER_SPAWN.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        RTPPlayer alice = player("alice", UUID.randomUUID(), Collections.emptySet());
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", alice, "rtp.effect.postteleport");

        assertEquals(1, tokens.size(), "non-VIP player should fall through to default");
        assertEquals("rtp.effect.postteleport.SOUND.LEVELUP.1.0.1.0", tokens.get(0));
    }

    // ---- 3. Permission gating: matched group wins, default suppressed ----

    @Test
    void permissionGatedGroupPreemptsDefault() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.LEVELUP.1.0.1.0\n" +
                "version: \"1.0\"\n");
        writeGroup("vip.yml",
                "when: postteleport\n" +
                "permission: rtp.vip\n" +
                "inherit:\n" +
                "  - DUMMY_NEVER_MATCHES\n" +
                "effects:\n" +
                "  - SOUND.WITHER_SPAWN.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        Set<String> perms = new HashSet<>();
        perms.add("rtp.vip");
        RTPPlayer bob = player("bob", UUID.randomUUID(), perms);
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", bob, "rtp.effect.postteleport");

        assertEquals(1, tokens.size(),
                "VIP with inherit:[] should produce only the VIP group's token (no default)");
        assertEquals("rtp.effect.postteleport.SOUND.WITHER_SPAWN.1.0.1.0", tokens.get(0));
    }

    // ---- 4. Players-allowlist gating (UUID + name) ----

    @Test
    void playersAllowlistGatesByUuid() throws IOException {
        UUID carolUuid = UUID.randomUUID();
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "version: \"1.0\"\n");
        writeGroup("staff.yml",
                "when: postteleport\n" +
                "players:\n" +
                "  - " + carolUuid + "\n" +
                "inherit:\n" +
                "  - DUMMY_NEVER_MATCHES\n" +
                "effects:\n" +
                "  - SOUND.STAFF.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        RTPPlayer carol = player("carol", carolUuid, Collections.emptySet());
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", carol, "rtp.effect.postteleport");
        assertEquals(1, tokens.size(), "UUID-allowlisted player should match the staff group");
        assertEquals("rtp.effect.postteleport.SOUND.STAFF.1.0.1.0", tokens.get(0));

        RTPPlayer dave = player("dave", UUID.randomUUID(), Collections.emptySet());
        List<String> daveTokens = EffectsResolver.resolveTokens(
                "postteleport", dave, "rtp.effect.postteleport");
        assertTrue(daveTokens.isEmpty(),
                "non-allowlisted player should not match staff (and default is empty)");
    }

    @Test
    void playersAllowlistGatesByName() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "version: \"1.0\"\n");
        writeGroup("named.yml",
                "when: postteleport\n" +
                "players:\n" +
                "  - eve\n" +
                "inherit:\n" +
                "  - DUMMY_NEVER_MATCHES\n" +
                "effects:\n" +
                "  - SOUND.EVE.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        RTPPlayer eve = player("eve", UUID.randomUUID(), Collections.emptySet());
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", eve, "rtp.effect.postteleport");
        assertEquals(1, tokens.size(), "name-allowlisted player should match");
        assertEquals("rtp.effect.postteleport.SOUND.EVE.1.0.1.0", tokens.get(0));
    }

    // ---- 5. inherit: chain prepends parent tokens ----

    @Test
    void inheritChainPrependsParentTokens() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.D.1.0.1.0\n" +
                "version: \"1.0\"\n");
        writeGroup("child.yml",
                "when: postteleport\n" +
                "permission: rtp.child\n" +
                "inherit:\n" +
                "  - default\n" +
                "effects:\n" +
                "  - SOUND.C.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        Set<String> perms = new HashSet<>();
        perms.add("rtp.child");
        RTPPlayer p = player("frank", UUID.randomUUID(), perms);
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", p, "rtp.effect.postteleport");

        assertEquals(2, tokens.size());
        // Parent tokens come first, then this group's own tokens.
        assertEquals("rtp.effect.postteleport.SOUND.D.1.0.1.0", tokens.get(0));
        assertEquals("rtp.effect.postteleport.SOUND.C.1.0.1.0", tokens.get(1));
    }

    // ---- 6. inherit: cycle is detected (no stack overflow) ----

    @Test
    void inheritCycleIsTruncatedNotStackOverflowed() throws IOException {
        writeGroup("a.yml",
                "when: postteleport\n" +
                "permission: rtp.a\n" +
                "inherit:\n" +
                "  - b\n" +
                "effects:\n" +
                "  - SOUND.A.1.0.1.0\n" +
                "version: \"1.0\"\n");
        writeGroup("b.yml",
                "when: postteleport\n" +
                "inherit:\n" +
                "  - a\n" +
                "effects:\n" +
                "  - SOUND.B.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        Set<String> perms = new HashSet<>();
        perms.add("rtp.a");
        RTPPlayer p = player("grace", UUID.randomUUID(), perms);

        // Must not StackOverflow - resolver's visited-set guards the cycle.
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", p, "rtp.effect.postteleport");
        assertNotNull(tokens, "cycle must not crash the resolver");
        // Both groups' own tokens should still be emitted exactly once each.
        assertTrue(tokens.contains("rtp.effect.postteleport.SOUND.A.1.0.1.0"));
        assertTrue(tokens.contains("rtp.effect.postteleport.SOUND.B.1.0.1.0"));
        assertEquals(2, tokens.size(),
                "cycle truncation must dedupe — each group emits its tokens at most once");
    }

    // ---- 7. Missing default: no NPE, no synthesis crash (S-006) ----

    @Test
    void noMatchingDefaultForStageYieldsEmpty() throws IOException {
        // The shipped default.yml has when: postteleport. For an unrelated
        // stage with no matching default-<stage> group, an ungated player
        // should resolve to an empty token list (not the postteleport default).
        // Verifies findDefaultForStage's stage-match guard.
        rebuildParser();

        RTPPlayer p = player("hank", UUID.randomUUID(), Collections.emptySet());
        List<String> tokens = EffectsResolver.resolveTokens(
                "queuepush", p, "rtp.effect.queuepush");
        assertNotNull(tokens, "S-006: resolver must never return null");
        assertTrue(tokens.isEmpty(),
                "stage with no matching default group must resolve to empty for an ungated player");
    }

    // ---- 8. No effects/ parser registered at all (cold start, S-006) ----

    @Test
    void noEffectsParserYieldsEmpty() {
        // setUp() did NOT register an EffectsGroupKeys parser this time.
        RTP.configs.multiConfigParserMap.remove(EffectsGroupKeys.class);
        RTPPlayer p = player("ivy", UUID.randomUUID(), Collections.emptySet());
        List<String> tokens = EffectsResolver.resolveTokens(
                "postteleport", p, "rtp.effect.postteleport");
        assertTrue(tokens.isEmpty(),
                "S-006: absent effects/ parser must yield empty (not NPE)");
    }

    // ---- 9. Hot reload: rebuilding the parser picks up new files ----

    @Test
    void hotReloadPicksUpNewGroupFile() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.OLD.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        RTPPlayer p = player("jack", UUID.randomUUID(), Collections.emptySet());
        List<String> before = EffectsResolver.resolveTokens(
                "postteleport", p, "rtp.effect.postteleport");
        assertEquals(1, before.size());
        assertEquals("rtp.effect.postteleport.SOUND.OLD.1.0.1.0", before.get(0));

        // Mutate disk + atomic-swap a fresh parser, mirroring /rtp reload.
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.NEW.1.0.1.0\n" +
                "  - PARTICLE.FLAME.16\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        List<String> after = EffectsResolver.resolveTokens(
                "postteleport", p, "rtp.effect.postteleport");
        assertEquals(2, after.size(), "reload should expose both new tokens");
        assertTrue(after.contains("rtp.effect.postteleport.SOUND.NEW.1.0.1.0"));
        assertTrue(after.contains("rtp.effect.postteleport.PARTICLE.FLAME.16"));
        assertFalse(after.contains("rtp.effect.postteleport.SOUND.OLD.1.0.1.0"),
                "stale token from the pre-reload parser must not leak");
    }

    // ---- 10. resolveUnioned merges with permission-derived nodes ----

    @Test
    void resolveUnionedMergesPermissionAndConfigTokens() throws IOException {
        writeGroup("default.yml",
                "when: postteleport\n" +
                "effects:\n" +
                "  - SOUND.D.1.0.1.0\n" +
                "version: \"1.0\"\n");
        rebuildParser();

        RTPPlayer p = player("kara", UUID.randomUUID(), Collections.emptySet());
        Collection<String> permNodes = List.of(
                "rtp.effect.postteleport.SOUND.PERM_ONLY.1.0.1.0");
        Collection<String> union = EffectsResolver.resolveUnioned(
                "postteleport", p, "rtp.effect.postteleport", permNodes);

        assertTrue(union.contains("rtp.effect.postteleport.SOUND.PERM_ONLY.1.0.1.0"),
                "permission-derived node must survive the union");
        assertTrue(union.contains("rtp.effect.postteleport.SOUND.D.1.0.1.0"),
                "config-derived token must be added by the union");
    }

    // ---- minimal RTPPlayer stub for the resolver's call sites ----

    private static final class StubPlayer implements RTPPlayer {
        private final String name;
        private final UUID uuid;
        private final Set<String> perms;

        StubPlayer(String name, UUID uuid, Set<String> perms) {
            this.name = name;
            this.uuid = uuid;
            this.perms = perms;
        }

        @Override public UUID uuid() { return uuid; }
        @Override public String name() { return name; }
        @Override public boolean hasPermission(String permission) {
            return perms != null && perms.contains(permission);
        }
        @Override public Set<String> getEffectivePermissions() {
            return perms == null ? Collections.emptySet() : perms;
        }
        @Override public void sendMessage(String message) { /* no-op */ }
        @Override public long cooldown() { return 0L; }
        @Override public long delay() { return 0L; }
        @Override public void performCommand(RTPPlayer player, String command) { /* no-op */ }
        @Override public RTPPlayer clone() { return this; }
        @Override public CompletableFuture<Boolean> setLocation(RTPLocation to) {
            return CompletableFuture.completedFuture(false);
        }
        @Override public RTPLocation getLocation() { return null; }
        @Override public boolean isOnline() { return true; }
    }
}
