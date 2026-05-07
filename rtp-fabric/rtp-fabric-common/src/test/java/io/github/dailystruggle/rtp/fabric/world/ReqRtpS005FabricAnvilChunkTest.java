package io.github.dailystruggle.rtp.fabric.world;

import io.github.dailystruggle.rtp.anvil.AnvilChunkView;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Collections;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * REQ-RTP-S-005 regression guard for the Fabric full-anvil-{@code RTPChunk}
 * path (rtp-fabric-ADR-005 follow-up that lifts the deferred full anvil view).
 *
 * <p>An Anvil-backed {@link FabricRTPChunk} must answer every block-data query
 * from the decoded {@link AnvilChunkView} without touching a {@code ChunkAccess}
 * — i.e. without forcing a synchronous chunk load on the server tick thread.
 * Forcing through any of the Minecraft accessors here would NPE on the
 * {@code super(null)} {@code chunk} field, which is exactly the failure mode
 * we want to surface if a future refactor accidentally drops the dual-mode
 * dispatch.</p>
 *
 * <p>This test deliberately does NOT call {@code Bootstrap.bootStrap()};
 * anvil-mode operations must not depend on the Minecraft registry being
 * initialised. If a regression introduces a registry lookup on the anvil
 * path, this class fails with the registry's "Not bootstrapped" exception.</p>
 */
@DisplayName("REQ-RTP-S-005 — FabricRTPChunk anvil-mode dispatch (no live load)")
class ReqRtpS005FabricAnvilChunkTest {

    private static final UUID WORLD_ID =
            UUID.nameUUIDFromBytes("test-world".getBytes());

    /** A self-contained, empty-sections view: no chunk data on disk. */
    private static AnvilChunkView emptyView() {
        // Two-arg constructor defaults biomeSections to an empty list (the
        // pre-Phase-2 baseline) and motionBlockingNoLeaves to null, which
        // makes getSurfaceHeight return the floor (0).
        return new AnvilChunkView(0, Collections.emptyList(), null);
    }

    @Test
    @DisplayName("anvil-backed constructor sets isAnvilBacked / isSelfContained / isLoaded")
    void modeFlags() {
        FabricRTPChunk c = new FabricRTPChunk(emptyView(), 3, -7, WORLD_ID, null);
        assertTrue(c.isAnvilBacked(), "must report anvil-backed");
        assertTrue(c.isSelfContained(), "ADR-015: anvil-backed instances are self-contained");
        assertFalse(c.isLoaded(), "an anvil-backed instance is by construction NOT loaded");
        assertTrue(c.isGenerated(), "the region file entry exists ⇒ generated");
        assertEquals(3, c.x());
        assertEquals(-7, c.z());
    }

    @Test
    @DisplayName("anvil-backed constructor rejects null view")
    void rejectsNullView() {
        assertThrows(IllegalArgumentException.class,
                () -> new FabricRTPChunk(null, 0, 0, WORLD_ID, null));
    }

    @Test
    @DisplayName("isAir / getSkyLight / getSurfaceHeight route through the view (no live access)")
    void blockQueriesRouteThroughView() {
        FabricRTPChunk c = new FabricRTPChunk(emptyView(), 0, 0, WORLD_ID, null);
        // Empty sections ⇒ every (x,y,z) is "above the highest emitted section"
        // ⇒ treated as air per AnvilChunkView contract. Live path would NPE on
        // a null ChunkAccess — that this returns true is the proof of dispatch.
        assertTrue(c.isAir(0, 64, 0));
        assertTrue(c.isAir(15, 0, 15));
        // Anvil-backed sky light is the vanilla "fully lit" default.
        assertEquals(15, c.getSkyLight(0, 64, 0));
        // Empty heightmap ⇒ floor (= minHeight = 0).
        assertEquals(0, c.getSurfaceHeight(0, 0));
    }

    @Test
    @DisplayName("isSafe with empty unsafe set short-circuits to true (no live access)")
    void isSafeEmptySet() {
        FabricRTPChunk c = new FabricRTPChunk(emptyView(), 0, 0, WORLD_ID, null);
        assertTrue(c.isSafe(0, 64, 0, Collections.emptySet()));
        assertTrue(c.isSafe(0, 64, 0, (Set<String>) null));
    }

    @Test
    @DisplayName("isSafe with non-empty unsafe set delegates to view (no live access)")
    void isSafeWithUnsafe() {
        FabricRTPChunk c = new FabricRTPChunk(emptyView(), 0, 0, WORLD_ID, null);
        // Empty view ⇒ blockIdAt returns null ⇒ AnvilChunkView treats out-of-range
        // as safe (cannot carry unsafe blocks).
        Set<String> reconciledUnsafe = Set.of("LAVA");
        assertTrue(c.isSafe(0, 64, 0, reconciledUnsafe));
    }

    @Test
    @DisplayName("keep / unload are no-ops on anvil-backed instances (no S-002 ticket churn)")
    void keepAndUnloadAreNoOps() {
        FabricRTPChunk c = new FabricRTPChunk(emptyView(), 0, 0, WORLD_ID, null);
        // No FabricRTPWorld registered against WORLD_ID and RTP.serverAccessor
        // is unset — if dispatch leaked into the live branch, the world lookup
        // would proceed (or NPE). The anvil branch returns immediately.
        assertDoesNotThrow(() -> c.keep(true));
        assertDoesNotThrow(() -> c.keep(false));
        assertDoesNotThrow(c::unload);
    }
}
