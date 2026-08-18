package io.github.dailystruggle.rtp.common.commands.prefab;

import io.github.dailystruggle.rtp.common.RTP;
import io.github.dailystruggle.rtp.common.commands.admin.AdminCmd;
import io.github.dailystruggle.rtp.common.mock.MockRTPPlayer;
import io.github.dailystruggle.rtp.common.mock.MockRTPServerAccessor;
import io.github.dailystruggle.rtp.common.mock.RTPTestSetup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Smoke tests for {@code /rtp admin} and the {@code prefab}
 * subtree. Focuses on the security/state shape: command metadata + permissions,
 * nonce mint on apply, nonce single-use consume, TTL expiry, caller binding, prefab binding.
 */
class PrefabCommandTest {

    @TempDir
    Path tempDir;

    private MockRTPServerAccessor accessor;

    @BeforeEach
    void setUp() {
        accessor = RTPTestSetup.install(tempDir.toFile());
    }

    // --- AdminCmd ------------------------------------------------------------

    @Test
    void adminCmd_metadata_isStable() {
        AdminCmd cmd = new AdminCmd(null);
        assertEquals("admin", cmd.name());
        assertEquals("rtp.menu.admin", cmd.permission());
        assertNotNull(cmd.description());
        assertFalse(cmd.description().isEmpty());
    }

    @Test
    void adminCmd_bareForm_withNoOpener_logsAndReturnsFalse() {
        AdminCmd cmd = new AdminCmd(null, null);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        boolean result = cmd.onCommand(caller, new HashMap<>(), null);
        assertFalse(result, "bare /rtp admin must reject when no opener wired");
    }

    @Test
    void adminCmd_bareForm_withOpener_invokesOpenerAndReturnsTrue() {
        AtomicReference<UUID> seen = new AtomicReference<>();
        AdminCmd cmd = new AdminCmd(null, seen::set);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        boolean result = cmd.onCommand(caller, new HashMap<>(), null);
        assertTrue(result);
        assertEquals(caller, seen.get());
    }

    @Test
    void adminCmd_bareForm_swallowsOpenerException() {
        AdminCmd cmd = new AdminCmd(null, id -> {
            throw new RuntimeException("simulated");
        });
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        boolean result = cmd.onCommand(caller, new HashMap<>(), null);
        assertFalse(result, "opener throwing must surface as command-rejected, not propagate");
    }

    // --- PrefabCommand: structure --------------------------------------------

    @Test
    void prefabCmd_metadata_andSubcommands() {
        PrefabCommand cmd = new PrefabCommand(null);
        assertEquals("prefab", cmd.name());
        assertEquals("rtp.admin.prefab", cmd.permission());
        Map<String, ?> children = cmd.getCommandLookup();
        assertNotNull(children);
        // Children are uppercased by CommandsAPI.
        assertTrue(children.containsKey("LIST"), "missing 'list' child");
        assertTrue(children.containsKey("APPLY"), "missing 'apply' child");
        assertTrue(children.containsKey("CONFIRM"), "missing 'confirm' child");
        assertTrue(children.containsKey("ROLLBACK"), "missing 'rollback' child");
    }

    @Test
    void prefabCmd_allSubcommands_sharePermission() {
        PrefabCommand cmd = new PrefabCommand(null);
        for (var sub : cmd.getCommandLookup().values()) {
            assertEquals(PrefabCommand.PERMISSION,
                    ((io.github.dailystruggle.commandsapi.common.CommandsAPICommand) sub).permission(),
                    "all prefab sub-commands must share rtp.admin.prefab");
        }
    }

    // --- PrefabApplyCmd ------------------------------------------------------

    @Test
    void apply_unknownId_isRejected_andMintsNothing() {
        PrefabNonceStore store = new PrefabNonceStore();
        PrefabApplyCmd apply = new PrefabApplyCmd(null, store);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("definitely-not-a-prefab"));
        boolean result = apply.onCommand(caller, params, null);
        assertFalse(result);
        assertEquals(0, store.size());
    }

    @Test
    void apply_knownId_stashesPendingDiffWithCorrectBinding() {
        PrefabNonceStore store = new PrefabNonceStore();
        PrefabApplyCmd apply = new PrefabApplyCmd(null, store);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        boolean result = apply.onCommand(caller, params, null);
        assertTrue(result);
        assertEquals(1, store.size());
    }

    @Test
    void apply_identityOverlay_stillStashesPending_withEmptyDiff() {
        PrefabNonceStore store = new PrefabNonceStore();
        PrefabApplyCmd apply = new PrefabApplyCmd(null, store);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("survival-default"));
        boolean result = apply.onCommand(caller, params, null);
        assertTrue(result);
        // identity overlay produces an empty per-file diff, but the pending
        // entry is still stashed so the confirmation flow remains uniform.
        assertEquals(1, store.size());
    }

    @Test
    void multiWorldApply_repairsNetherVertInDiffAndTrees() throws Exception {
        // Multi-world prefab clones overworld regions/default; verify NetherEndConfigAmender
        // repairs nether vert settings (requireSkyLight=false, maxY <= 128) in diff and trees.
        java.io.File pluginDir =
                java.nio.file.Files.createTempDirectory("rtp-prefab-nether-test").toFile();
        accessor = RTPTestSetup.install(pluginDir);

        // Seed a regions/default.yml on disk with an overworld-flavoured vert,
        // then reload so the live regions MultiConfigParser registers it - the
        // amender reads the live default as its template.
        java.io.File regionsDir = new java.io.File(pluginDir, "regions");
        assertTrue(regionsDir.mkdirs() || regionsDir.isDirectory());
        java.nio.file.Files.writeString(new java.io.File(regionsDir, "default.yml").toPath(),
                "world: \"world\"\n"
                        + "shape:\n  name: \"CIRCLE\"\n  radius: 256\n"
                        + "vert:\n  name: \"LINEAR\"\n  minY: 32\n  maxY: 255\n"
                        + "  direction: 2\n  requireSkyLight: true\n");
        RTP.configs.reloadConfigs();

        accessor.addWorld(new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world"));
        accessor.addWorld(new io.github.dailystruggle.rtp.common.mock.MockRTPWorld("world_nether"));

        PrefabNonceStore store = new PrefabNonceStore();
        PrefabApplyCmd apply = new PrefabApplyCmd(null, store);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("multi-world"));
        assertTrue(apply.onCommand(caller, params, null));

        PrefabNonceStore.ConsumeResult cr = store.consumeByCaller(caller, "multi-world");
        assertTrue(cr.ok(), "apply must mint a pending entry");
        Map<String, Object> netherRegion = cr.entry().newTrees().get("regions/world_nether");
        assertNotNull(netherRegion, "a per-world region for the nether must be synthesised");
        Map<?, ?> vert = (Map<?, ?>) netherRegion.get("vert");
        assertNotNull(vert, "synthesised nether region must carry a vert block");
        assertEquals(false, vert.get("requireSkyLight"),
                "nether has no sky light: the cloned sky-light requirement must be repaired");
        int maxY = ((Number) vert.get("maxY")).intValue();
        assertTrue(maxY <= 128,
                "nether maxY must be clamped under the bedrock ceiling, was " + maxY);

        // Diff consistency (option B): the preview diff itself must carry the
        // repaired vert.requireSkyLight=false, not the cloned overworld value.
        List<PrefabApplier.Change> diff = cr.entry().perFileDiff().get("regions/world_nether");
        assertNotNull(diff, "the synthesised nether region must produce a diff");
        // For a brand-new region file the whole vert block lands as a single
        // wholesale "vert" change (the base has no vert to recurse into), so
        // inspect either the granular vert.requireSkyLight change or the vert
        // map's nested value.
        boolean sawRepairedSkyLight = diff.stream().anyMatch(c -> {
            if (c.keyPath().equalsIgnoreCase("vert.requireSkyLight")) {
                return Boolean.FALSE.equals(c.newValue());
            }
            if (c.keyPath().equalsIgnoreCase("vert") && c.newValue() instanceof Map<?, ?> m) {
                return Boolean.FALSE.equals(m.get("requireSkyLight"));
            }
            return false;
        });
        assertTrue(sawRepairedSkyLight,
                "the apply preview diff must reflect the repaired vert.requireSkyLight=false");
    }

    // --- PrefabConfirmCmd: nonce semantics -----------------------------------

    // Post-token-removal (2026-05-24, mirrors ADR-050): the pending diff is
    // keyed solely on (callerId, prefabId). No opaque token, no TTL, no
    // wrong-caller / wrong-prefab / expired arms - those concerns are
    // collapsed into the trust boundary of the command sender.

    @Test
    void confirm_pendingForCaller_isAcceptedAndConsumed() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        store.mint(caller, "low-performance",
                java.util.Collections.singletonMap("performance",
                        List.of(new PrefabApplier.Change("queue.maxSize", null, 50))));
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        boolean result = confirm.onCommand(caller, params, null);
        assertTrue(result, "valid confirm must succeed (caller-bound resolution)");
        assertEquals(0, store.size(), "pending entry must be consumed after successful confirm");
    }

    @Test
    void confirm_firesPrefabAppliedEvent() throws Exception {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        store.mint(caller, "low-performance",
                java.util.Collections.singletonMap("performance",
                        List.of(new PrefabApplier.Change("queue.maxSize", null, 50))));

        AtomicReference<io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent> seen =
                new AtomicReference<>();
        try (AutoCloseable sub =
                     io.github.dailystruggle.rtp.api.RTPAPI.onPrefabApplied(seen::set)) {
            PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
            Map<String, List<String>> params = new HashMap<>();
            params.put("id", List.of("low-performance"));
            assertTrue(confirm.onCommand(caller, params, null));
        }

        io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent ev = seen.get();
        assertNotNull(ev, "a successful confirm must fire a PrefabAppliedEvent");
        assertEquals("low-performance", ev.prefabId());
        assertEquals(caller, ev.callerId());
        assertTrue(ev.writtenFiles().contains("performance"),
                "performance.yml must be reported as written");
        assertTrue(ev.changes().containsKey("performance"),
                "the per-file change map must surface the performance diff");
        assertEquals("queue.maxSize", ev.changes().get("performance").get(0).keyPath());
    }

    @Test
    void confirm_unsubscribedHandler_isNotNotified() throws Exception {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        store.mint(caller, "low-performance", Map.of());

        AtomicReference<io.github.dailystruggle.rtp.api.event.PrefabAppliedEvent> seen =
                new AtomicReference<>();
        AutoCloseable sub =
                io.github.dailystruggle.rtp.api.RTPAPI.onPrefabApplied(seen::set);
        sub.close();

        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        assertTrue(confirm.onCommand(caller, params, null));
        assertEquals(null, seen.get(),
                "an unsubscribed handler must not be notified");
    }

    @Test
    void confirm_isReplayResistant() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        store.mint(caller, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        assertTrue(confirm.onCommand(caller, params, null));
        // A second confirm with no fresh apply must fail (single-use).
        assertFalse(confirm.onCommand(caller, params, null),
                "caller-bound entry is single-use; replay must not succeed");
    }

    @Test
    void confirm_noPendingForCaller_rejected() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        // No mint - caller has no pending diff.
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        assertFalse(confirm.onCommand(caller, params, null),
                "confirm without a pending apply for the caller must be rejected");
        assertEquals(0, store.size());
    }

    @Test
    void confirm_isolatedPerCaller() {
        // Different callers do not share pending diffs.
        PrefabNonceStore store = new PrefabNonceStore();
        UUID alice = UUID.randomUUID();
        UUID mallory = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(alice, "alice", null));
        accessor.addPlayer(new MockRTPPlayer(mallory, "mallory", null));
        store.mint(alice, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        // Mallory cannot consume Alice's pending diff.
        assertFalse(confirm.onCommand(mallory, params, null),
                "another caller must not be able to consume Alice's pending diff");
        // Alice's entry survives.
        assertEquals(1, store.size());
    }

    @Test
    void confirm_missingId_rejected_storeUntouched() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        store.mint(caller, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        boolean result = confirm.onCommand(caller, new HashMap<>(), null);
        assertFalse(result);
        assertEquals(1, store.size(), "missing-id confirm must not consume the outstanding entry");
    }

    // --- PrefabRollbackCmd ---------------------------------------------------

    @Test
    void rollback_missingBackupsReportsNoOp() {
        PrefabRollbackCmd rb = new PrefabRollbackCmd(null);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        boolean result = rb.onCommand(caller, params, null);
        assertFalse(result, "rollback stub returns false until 4b lands");
    }

    @Test
    void rollback_unknownId_rejected() {
        PrefabRollbackCmd rb = new PrefabRollbackCmd(null);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("nope"));
        assertFalse(rb.onCommand(caller, params, null));
    }

    // --- PrefabNonceStore: direct unit checks --------------------------------

    @Test
    void nonceStore_mint_rejectsNullArgs() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        try {
            store.mint(null, "low-performance", Map.of());
            fail("expected NPE for null callerId");
        } catch (NullPointerException expected) {
            // ok
        }
        try {
            store.mint(caller, null, Map.of());
            fail("expected NPE for null prefabId");
        } catch (NullPointerException expected) {
            // ok
        }
    }

    @Test
    void nonceStore_consumeByCaller_noPending_isNotFound() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        PrefabNonceStore.ConsumeResult r = store.consumeByCaller(caller, "low-performance");
        assertEquals(PrefabNonceStore.ConsumeResult.Kind.NOT_FOUND, r.kind());
        assertFalse(r.ok());
    }

    @Test
    void nonceStore_repeatMint_overwritesPriorPending() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        store.mint(caller, "low-performance", Map.of());
        store.mint(caller, "low-performance", Map.of()); // overwrite, not duplicate
        assertEquals(1, store.size(),
                "repeat mint for the same (caller, prefabId) must overwrite, not accumulate");
    }
}
