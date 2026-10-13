package io.github.dailystruggle.rtp.common.tasks.teleport;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import io.github.dailystruggle.rtp.api.schematic.LoadedSchematic;
import io.github.dailystruggle.rtp.api.schematic.PasteOptions;
import io.github.dailystruggle.rtp.api.schematic.SchematicSource;
import io.github.dailystruggle.rtp.api.world.RTPLocation;
import io.github.dailystruggle.rtp.common.selection.region.GlobalRegionVerifiers;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * REQ-RTP-S-003 - region-schematic paste must respect claims (ADR-058). The teleport pipeline
 * runs the schematic's horizontal footprint through {@link GlobalRegionVerifiers} (the sanctioned
 * claim/protection registry the bundled claim integrations register into) before writing any
 * block, and suppresses the paste when any footprint cell is inside a claim. These tests exercise
 * {@link TeleportPipelineTask#schematicFootprintClearAsync} and the non-blocking verdict read
 * {@link TeleportPipelineTask#footprintVerdictClear} directly.
 */
class ReqRtpS003SchematicFootprintClaimTest {

  private static final String WORLD = "world";

  /** Minimal decoded-schematic stand-in: only the bounding box matters to the footprint guard. */
  private record FixedSizeSchematic(int width, int height, int length) implements LoadedSchematic {
    @Override
    public SchematicSource source() {
      return null;
    }
  }

  private static boolean clear(LoadedSchematic schem, RTPLocation at) throws Exception {
    return TeleportPipelineTask.schematicFootprintClearAsync(
        schem, at, PasteOptions.defaults(), WORLD).get(5, TimeUnit.SECONDS);
  }

  @BeforeEach
  void setUp() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @AfterEach
  void tearDown() {
    GlobalRegionVerifiers.clearGlobalRegionVerifiers();
  }

  @Test
  @DisplayName("no registered verifiers -> footprint is clear (paste proceeds)")
  void noVerifiersMeansClear() throws Exception {
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertTrue(clear(schem, at));
  }

  @Test
  @DisplayName("footprint fully outside every claim -> clear (paste proceeds)")
  void footprintOutsideClaimIsClear() throws Exception {
    // Reject only the column at x=0,z=0 (far from the footprint around 100,200).
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 0 && c.z() == 0));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertTrue(clear(schem, at));
  }

  @Test
  @DisplayName("footprint cell inside a claim -> not clear (paste suppressed, S-003)")
  void footprintInsideClaimIsRejected() throws Exception {
    // BOTTOM_CENTER anchor: a 5x5 footprint around (100,200) spans x/z 98..102.
    // Reject exactly the arrival center cell -> the footprint intersects the claim.
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 100 && c.z() == 200));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertFalse(clear(schem, at));
  }

  @Test
  @DisplayName("claim touching only the footprint edge is still detected")
  void claimAtFootprintEdgeIsRejected() throws Exception {
    // The +x/+z edge of the 5x5 BOTTOM_CENTER footprint is x=102, z=202.
    GlobalRegionVerifiers.addGlobalRegionVerifier(c -> !(c.x() == 102 && c.z() == 202));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertFalse(clear(schem, at));
  }

  @Test
  @DisplayName("an async (I/O-style) claim verifier is honoured")
  void asyncVerifierIsHonoured() throws Exception {
    // An async verifier rejecting the footprint center must suppress the paste just like a
    // synchronous one (claim plugins may answer off-thread).
    GlobalRegionVerifiers.addGlobalRegionVerifierAsync(c ->
        java.util.concurrent.CompletableFuture.completedFuture(!(c.x() == 100 && c.z() == 200)));
    LoadedSchematic schem = new FixedSizeSchematic(5, 1, 5);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);
    assertFalse(clear(schem, at));
  }

  @Test
  @DisplayName("REQ-RTP-S-003 / S-005: a pending claim verifier never blocks; the verdict reads not-clear until it answers")
  void pendingVerifierDoesNotBlockAndSuppressesPaste() throws Exception {
    CompletableFuture<Boolean> gate = new CompletableFuture<>();
    GlobalRegionVerifiers.addGlobalRegionVerifierAsync(c -> gate);
    LoadedSchematic schem = new FixedSizeSchematic(3, 1, 3);
    RTPLocation at = new RTPLocation(null, 100, 64, 200);

    // Returns immediately (the old per-cell join() would have blocked this thread forever).
    CompletableFuture<Boolean> verdict = TeleportPipelineTask.schematicFootprintClearAsync(
        schem, at, PasteOptions.defaults(), WORLD);
    assertFalse(verdict.isDone(), "verdict must stay pending while the verifier is pending");
    assertFalse(TeleportPipelineTask.footprintVerdictClear(verdict),
        "a pending verdict must read as not clear (paste suppressed, S-003 fail-safe)");

    gate.complete(Boolean.TRUE);
    assertTrue(verdict.get(5, TimeUnit.SECONDS));
    assertTrue(TeleportPipelineTask.footprintVerdictClear(verdict));
  }

  @Test
  @DisplayName("REQ-RTP-S-003: a failed or missing verdict reads as not clear")
  void failedVerdictIsNotClear() {
    assertFalse(TeleportPipelineTask.footprintVerdictClear(null));
    assertFalse(TeleportPipelineTask.footprintVerdictClear(
        CompletableFuture.failedFuture(new IllegalStateException("verifier down"))));
    assertFalse(TeleportPipelineTask.footprintVerdictClear(
        CompletableFuture.completedFuture(Boolean.FALSE)));
  }
}
