package io.github.dailystruggle.rtp.fabric.player;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * MULTI_PLATFORM_PLAN Step F sub-item 5 — Permission node parity test vs. the
 * Bukkit adapter.
 *
 * <p><b>Why this exists.</b> {@link FabricDefaultPermissions} is the Fabric-side
 * substitute for the {@code plugin.yml} {@code permissions:} default table that
 * Bukkit / Paper / Folia / Spigot consume automatically. On Bukkit
 * {@code BukkitRTPPlayer#hasPermission} simply delegates to
 * {@code Player#hasPermission}, which the server has already initialised against
 * {@code plugin.yml}'s declared {@code default:} values
 * ({@code true} / {@code false} / {@code op}). Fabric has no equivalent
 * descriptor — {@link FabricRTPPlayer#hasPermission} consults
 * {@link FabricDefaultPermissions#resolve} as its second tier (after
 * {@code fabric-permissions-api}, before the {@code ops.json} fallback). If the
 * Fabric table drifts from {@code plugin.yml}, baseline player permissions like
 * {@code rtp.see} / {@code rtp.use} are silently denied (or the inverse — an
 * op-only permission becomes a default-grant) without any server log.
 *
 * <p><b>What this test pins.</b> For the canonical node set declared in
 * {@code plugin.yml}, the Fabric default-table verdict must mirror what a
 * vanilla Bukkit server would compute from {@code plugin.yml} when no perms
 * implementer is registered:
 * <ul>
 *   <li>{@code default: true}   → {@link FabricDefaultPermissions.Verdict#TRUE}</li>
 *   <li>{@code default: false}  → {@link FabricDefaultPermissions.Verdict#FALSE}</li>
 *   <li>{@code default: op} or omitted → {@link FabricDefaultPermissions.Verdict#OP}</li>
 * </ul>
 *
 * <p><b>Scope and limits.</b> This is a structural parity guard, not a runtime
 * equivalence test. The full Fabric chain ({@code fabric-permissions-api} →
 * {@code FabricDefaultPermissions} → {@code ops.json}) needs a live
 * {@code MinecraftServer} + {@code ServerPlayer} handle which is out of scope
 * for a unit test (and the perms-api / ops.json tiers are themselves stable
 * surfaces; the regression risk lives almost entirely in the middle tier this
 * file pins). The Bukkit adapter is not invoked here — {@code BukkitRTPPlayer}
 * is a one-line delegate to {@code Player#hasPermission}, so "parity vs Bukkit"
 * reduces to "parity vs {@code plugin.yml}". An earlier revision tried a
 * best-effort classpath lookup of {@code /plugin.yml}; that was removed when
 * it surfaced that multiple modules ship a top-level {@code plugin.yml}
 * resource and the lookup was non-deterministic.
 *
 * <p><b>Source of truth.</b> {@code rtp-plugin/src/main/resources/plugin.yml}.
 * The canonical node set below mirrors the {@code permissions:} block; if a
 * new node is added there, mirror it both into {@link FabricDefaultPermissions}
 * and into {@link #EXPECTED_DEFAULTS} so the parity guard keeps biting.
 *
 * <p><b>Traceability.</b> Candidate {@code REQ-RTP-F-???} — permission semantics
 * parity across Bukkit and Fabric adapters. No existing REQ-* row covers this
 * surface; row to be added in {@code REQUIREMENTS.md §F} as a follow-up to
 * Step F's closure.
 */
class FabricDefaultPermissionsParityTest {

    /**
     * Canonical node set from {@code rtp-plugin/src/main/resources/plugin.yml},
     * paired with the verdict a vanilla Bukkit server would apply when no
     * permission attachment overrides the node. The {@link LinkedHashMap}
     * preserves the {@code plugin.yml} declaration order for readable failure
     * output.
     *
     * <p>Nodes omitted from {@code plugin.yml}'s {@code default:} attribute
     * (the majority) resolve to {@link FabricDefaultPermissions.Verdict#OP} —
     * that is Bukkit's documented behaviour for an undefaulted permission.
     */
    private static final Map<String, FabricDefaultPermissions.Verdict> EXPECTED_DEFAULTS =
            buildExpected();

    private static Map<String, FabricDefaultPermissions.Verdict> buildExpected() {
        Map<String, FabricDefaultPermissions.Verdict> m = new LinkedHashMap<>();
        // default: true
        m.put("rtp.see", FabricDefaultPermissions.Verdict.TRUE);
        m.put("rtp.use", FabricDefaultPermissions.Verdict.TRUE);
        // default: false (the rtp.onevent.* opt-in family + their parent aggregate)
        m.put("rtp.onevent.join", FabricDefaultPermissions.Verdict.FALSE);
        m.put("rtp.onevent.firstjoin", FabricDefaultPermissions.Verdict.FALSE);
        m.put("rtp.onevent.respawn", FabricDefaultPermissions.Verdict.FALSE);
        m.put("rtp.onevent.changeworld", FabricDefaultPermissions.Verdict.FALSE);
        m.put("rtp.onevent.move", FabricDefaultPermissions.Verdict.FALSE);
        m.put("rtp.onevent.teleport", FabricDefaultPermissions.Verdict.FALSE);
        m.put("rtp.onevent.*", FabricDefaultPermissions.Verdict.FALSE);
        // Bukkit "no default attribute" => op. These nodes are declared in
        // plugin.yml with no `default:` key.
        m.put("rtp.free", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.reload", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.config", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.scan", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.noDelay", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.noDelay.chunks", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.noCancel", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.noCooldown", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.other", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.notme", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.world", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.worlds.*", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.region", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.regions.*", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.unqueued", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.params", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.biome", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.biome.free", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.biome.*", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.info", FabricDefaultPermissions.Verdict.OP);
        m.put("rtp.*", FabricDefaultPermissions.Verdict.OP);
        // rtp.delay / rtp.cooldown are placeholders — plugin.yml has no default,
        // so Bukkit treats them as op. FabricDefaultPermissions DENIES them
        // (Verdict.FALSE) because they exist solely as base-keys for the
        // numeric-suffix override system (e.g. rtp.delay.5) and granting them
        // op-implicit-true would short-circuit the suffix lookup. This is an
        // intentional Fabric-side tightening and is asserted separately so the
        // intent is documented rather than buried.
        return m;
    }

    @Nested
    @DisplayName("FabricDefaultPermissions vs plugin.yml declared defaults")
    class PluginYmlParity {

        @Test
        @DisplayName("every canonical node resolves to the plugin.yml-declared verdict")
        void canonicalNodes_resolveToDeclaredDefaults() {
            StringBuilder mismatches = new StringBuilder();
            for (Map.Entry<String, FabricDefaultPermissions.Verdict> e : EXPECTED_DEFAULTS.entrySet()) {
                FabricDefaultPermissions.Verdict actual = FabricDefaultPermissions.resolve(e.getKey());
                if (actual != e.getValue()) {
                    mismatches.append("  ").append(e.getKey())
                            .append(" — expected ").append(e.getValue())
                            .append(", got ").append(actual).append('\n');
                }
            }
            if (mismatches.length() > 0) {
                throw new AssertionError(
                        "FabricDefaultPermissions has drifted from plugin.yml declared defaults:\n"
                                + mismatches
                                + "Update FabricDefaultPermissions (rtp-fabric-common) and this test's "
                                + "EXPECTED_DEFAULTS table together; both trace plugin.yml as their "
                                + "source of truth.");
            }
        }

        @Test
        @DisplayName("rtp.delay / rtp.cooldown are intentionally FALSE on Fabric (placeholder base-keys)")
        void delayAndCooldownAreIntentionallyDenied() {
            // Documented Fabric-side tightening: these nodes only exist as base
            // keys for numeric-suffix overrides (rtp.delay.<N>, rtp.cooldown.<N>).
            // Granting the bare node would short-circuit the suffix lookup.
            assertEquals(FabricDefaultPermissions.Verdict.FALSE,
                    FabricDefaultPermissions.resolve("rtp.delay"),
                    "rtp.delay base key shall be denied so numeric-suffix overrides drive the actual value");
            assertEquals(FabricDefaultPermissions.Verdict.FALSE,
                    FabricDefaultPermissions.resolve("rtp.cooldown"),
                    "rtp.cooldown base key shall be denied so numeric-suffix overrides drive the actual value");
        }

        @Test
        @DisplayName("rtp.personalqueue is FALSE on Fabric (per ADR-043 opt-in semantics)")
        void personalQueueIsDenied() {
            // ADR-043: rtp.personalqueue is an opt-in. plugin.yml does not give
            // it a default attribute, so on Bukkit it's "op". Fabric tightens to
            // FALSE so an op does NOT implicitly carry the per-player bucket
            // lifecycle (which is a per-player intent, not an admin grant).
            // If this changes, update FabricDefaultPermissions and this assertion
            // together with an ADR-043 amendment.
            assertEquals(FabricDefaultPermissions.Verdict.FALSE,
                    FabricDefaultPermissions.resolve("rtp.personalqueue"));
        }
    }

    @Nested
    @DisplayName("Verdict.resolve() edge cases")
    class EdgeCases {

        @Test
        @DisplayName("null and empty nodes resolve to TRUE (no-op gate)")
        void nullAndEmptyResolveTrue() {
            assertEquals(FabricDefaultPermissions.Verdict.TRUE,
                    FabricDefaultPermissions.resolve(null),
                    "null node short-circuits to TRUE (no-op gate; matches FabricRTPPlayer.hasPermission early-return)");
            assertEquals(FabricDefaultPermissions.Verdict.TRUE,
                    FabricDefaultPermissions.resolve(""),
                    "empty node short-circuits to TRUE (same early-return semantics)");
        }

        @Test
        @DisplayName("unknown nodes resolve to OP (Bukkit default for omitted defaults:)")
        void unknownNodesResolveOp() {
            // Matches Bukkit's documented behaviour: a permission with no
            // `default:` attribute is op-only. Critical for addon-defined nodes
            // and wildcard-expanded children (e.g. rtp.worlds.<world_name>).
            assertEquals(FabricDefaultPermissions.Verdict.OP,
                    FabricDefaultPermissions.resolve("rtp.worlds.world"));
            assertEquals(FabricDefaultPermissions.Verdict.OP,
                    FabricDefaultPermissions.resolve("rtp.regions.spawn_region"));
            assertEquals(FabricDefaultPermissions.Verdict.OP,
                    FabricDefaultPermissions.resolve("rtp.biome.minecraft.plains"));
            assertEquals(FabricDefaultPermissions.Verdict.OP,
                    FabricDefaultPermissions.resolve("some.addon.node"));
        }
    }

    // Note: a previous revision of this test attempted a best-effort
    // classpath sanity check against `/plugin.yml`. That was removed because
    // `rtp-fabric-common`'s test classpath surfaces commands-api's much
    // smaller `plugin.yml` first (multiple modules ship a top-level
    // `plugin.yml` resource), so the substring lookup would either get the
    // wrong file or pass for the wrong reasons. The structural parity check
    // in `PluginYmlParity#canonicalNodes_resolveToDeclaredDefaults` remains
    // authoritative: it pins the Fabric default table against an explicit,
    // in-test mirror of the rtp-plugin `plugin.yml` declared defaults, with
    // a maintenance note above EXPECTED_DEFAULTS calling out the mirror
    // contract.

    /**
     * Smoke: the public API surface remains stable (Verdict enum cardinality,
     * static {@code resolve}). If this trips, downstream consumers (the three
     * {@code FabricRTPPlayer} variants + future ADR-013 enumeration impl) will
     * break — this assertion exists so the regression surfaces here first.
     */
    @Test
    @DisplayName("Verdict enum cardinality is {TRUE, FALSE, OP}")
    void verdictEnumCardinalityIsStable() {
        FabricDefaultPermissions.Verdict[] values = FabricDefaultPermissions.Verdict.values();
        assertEquals(3, values.length,
                "Verdict enum cardinality must remain 3 — adding a new state requires "
                        + "updating every FabricRTPPlayer variant's hasPermission chain "
                        + "and the ADR-013 enumeration design.");
        assertNotNull(Set.of(values).containsAll(Set.of(
                FabricDefaultPermissions.Verdict.TRUE,
                FabricDefaultPermissions.Verdict.FALSE,
                FabricDefaultPermissions.Verdict.OP)) ? "ok" : null);
    }
}
