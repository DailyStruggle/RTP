package io.github.dailystruggle.rtp.common.commands.prefab;

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
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * Session 4a smoke tests for {@code /rtp admin} and the {@code prefab}
 * subtree. Focuses on the security/state shape that survives Session 4b's
 * disk-write wiring: command metadata + permissions, nonce mint on apply,
 * nonce single-use consume, TTL expiry, caller binding, prefab binding.
 *
 * <p>Session 4b will add a sibling {@code PrefabOnDiskWriteTest} that
 * exercises the atomic-rename + {@code .bak.&lt;ts&gt;} retention path.
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
    void apply_knownId_mintsNonceWithCorrectBinding() {
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
    void apply_identityOverlay_stillMintsNonce_withEmptyDiff() {
        PrefabNonceStore store = new PrefabNonceStore();
        PrefabApplyCmd apply = new PrefabApplyCmd(null, store);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("survival-default"));
        boolean result = apply.onCommand(caller, params, null);
        assertTrue(result);
        // identity overlay produces an empty per-file diff, but the nonce is
        // still minted so the confirmation flow remains uniform.
        assertEquals(1, store.size());
    }

    // --- PrefabConfirmCmd: nonce semantics -----------------------------------

    @Test
    void confirm_validToken_isAcceptedAndConsumed() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        PrefabNonceStore.Entry e = store.mint(caller, "low-performance",
                java.util.Collections.singletonMap("performance",
                        List.of(new PrefabApplier.Change("queue.maxSize", null, 50))));
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        params.put("token", List.of(e.token()));
        boolean result = confirm.onCommand(caller, params, null);
        assertTrue(result, "valid confirm must succeed (4a stub returns true on accepted)");
        assertEquals(0, store.size(), "nonce must be consumed after successful confirm");
    }

    @Test
    void confirm_isReplayResistant() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        PrefabNonceStore.Entry e = store.mint(caller, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        params.put("token", List.of(e.token()));
        assertTrue(confirm.onCommand(caller, params, null));
        // Replay with same token must fail.
        assertFalse(confirm.onCommand(caller, params, null),
                "single-use nonce must not be replayable");
    }

    @Test
    void confirm_wrongCaller_rejected_andTokenIsBurned() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID minter = UUID.randomUUID();
        UUID interloper = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(minter, "alice", null));
        accessor.addPlayer(new MockRTPPlayer(interloper, "mallory", null));
        PrefabNonceStore.Entry e = store.mint(minter, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        params.put("token", List.of(e.token()));
        assertFalse(confirm.onCommand(interloper, params, null),
                "wrong caller must be rejected");
        // The wrong-caller path burns the token (defence-in-depth).
        assertEquals(0, store.size(),
                "nonce must be consumed even on wrong-caller rejection to prevent grinding");
    }

    @Test
    void confirm_wrongPrefab_rejected() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        PrefabNonceStore.Entry e = store.mint(caller, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("high-performance"));
        params.put("token", List.of(e.token()));
        assertFalse(confirm.onCommand(caller, params, null),
                "mismatched prefab id must be rejected");
        assertEquals(0, store.size(), "nonce must be burned on prefab-mismatch");
    }

    @Test
    void confirm_expiredToken_isRejected() {
        AtomicLong clock = new AtomicLong(1_000_000L);
        PrefabNonceStore store = new PrefabNonceStore(clock::get, 100L);
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        PrefabNonceStore.Entry e = store.mint(caller, "low-performance", Map.of());
        // Advance the clock past the TTL. The sweep inside consume() will
        // evict the entry before the kind check, so we expect NOT_FOUND.
        clock.set(clock.get() + 200L);
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        Map<String, List<String>> params = new HashMap<>();
        params.put("id", List.of("low-performance"));
        params.put("token", List.of(e.token()));
        assertFalse(confirm.onCommand(caller, params, null),
                "expired token must be rejected (NOT_FOUND after sweep)");
        assertEquals(0, store.size());
    }

    @Test
    void confirm_missingArgs_rejected_storeUntouched() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        accessor.addPlayer(new MockRTPPlayer(caller, "admin", null));
        store.mint(caller, "low-performance", Map.of());
        PrefabConfirmCmd confirm = new PrefabConfirmCmd(null, store);
        boolean result = confirm.onCommand(caller, new HashMap<>(), null);
        assertFalse(result);
        assertEquals(1, store.size(), "missing-args confirm must not consume the outstanding nonce");
    }

    // --- PrefabRollbackCmd ---------------------------------------------------

    @Test
    void rollback_isStubInSession4a() {
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
    void nonceStore_consume_unknownToken_isNotFound() {
        PrefabNonceStore store = new PrefabNonceStore();
        UUID caller = UUID.randomUUID();
        PrefabNonceStore.ConsumeResult r = store.consume("no-such-token", caller, "low-performance");
        assertEquals(PrefabNonceStore.ConsumeResult.Kind.NOT_FOUND, r.kind());
        assertFalse(r.ok());
    }
}
